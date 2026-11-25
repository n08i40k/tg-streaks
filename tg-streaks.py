import base64
import fcntl
import hashlib
import lzma
import os
import shutil
import threading
import traceback
import zipfile
from typing import Optional, cast

from java import dynamic_proxy
from android.content import Intent
from android.content import DialogInterface
from android.net import Uri
from android.os import Process
from android.util import Log
from android.webkit import ValueCallback
from android_utils import copy_to_clipboard, run_on_ui_thread
from base_plugin import BasePlugin
from client_utils import get_last_fragment
from dalvik.system import InMemoryDexClassLoader
from java.lang import Boolean, Class, Runnable, String
from java.nio import ByteBuffer
from java.util import Locale
from org.telegram.messenger import ApplicationLoader, LocaleController
from org.telegram.ui.ActionBar import AlertDialog
from typing_extensions import Any
from ui.bulletin import BulletinHelper

# fmt: off

__id__ = "tg-streaks"
__name__ = "Streaks"
__description__ = "Аналог стриков TikTok для Telegram"
__author__ = "@n08i40k_extera & @RoflPlugins"
__version__ = "2.22.1"
__icon__ = "tiktok_streak/4"
__min_version__ = "12.1.1"

# Core constants

DEBUG_MODE = False
LOGCAT_TAG = __id__

# Resource hashes
DEX_BLOCK = ("# === EMDEDDED DEX BEGIN ===", "# === EMDEDDED DEX END ===")
RESOURCES_BLOCK = ("# === EMDEDDED RESOURCES BEGIN ===", "# === EMDEDDED RESOURCES END ===")

# Plugin official resource links

PLUGIN_UPDATE_TG_URL = "tg://resolve?domain=n08i40k_extera&post=3"
PLUGIN_CHAT_TG_URL = "tg://resolve?domain=n08i40k_extera_chat"

# Other constants

SETTING_LAST_LOADED_VERSION = "last_loaded_version"
VALID_ISO_LANGUAGES = frozenset(str(code) for code in Locale.getISOLanguages())


def get_plugin_cache_dir(*parts: str) -> str:
    cache_root = ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()
    return os.path.join(cache_root, __id__, *parts)


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().lower()


def _version_tuple(value: str) -> tuple[int, ...]:
    parts: list[int] = []

    for chunk in str(value).strip().lstrip("vV").split("."):
        digits = ""

        for char in chunk:
            if not char.isdigit():
                break
            digits += char

        parts.append(int(digits) if digits else 0)

    return tuple(parts)


def _is_version_older(version: str, required: str) -> bool:
    left = _version_tuple(version)
    right = _version_tuple(required)
    width = max(len(left), len(right))

    return left + (0,) * (width - len(left)) < right + (0,) * (width - len(right))


I18N_STATUS: dict[str, dict[str, str]] = {
    'status.error.assets.unpack_failed': {'en': 'Failed to unpack plugin resources', 'ru': 'Не удалось распаковать ресурсы плагина'},
    'status.error.update.open_link_failed': {'en': "Couldn't open update link", 'ru': 'Не удалось открыть ссылку обновления'},
    'status.error.update.restart_failed': {'en': "Couldn't restart client", 'ru': 'Не удалось перезапустить клиент'},
}

I18N_DIALOGS: dict[str, dict[str, str]] = {
    'dialog.assets_damaged.message': {'en': '{filename} is missing from the plugin file or is damaged. Reinstall the plugin.', 'ru': '{filename} отсутствует в файле плагина или повреждён. Переустановите плагин.'},
    'dialog.assets_damaged.ok': {'en': 'OK', 'ru': 'Ок'},
    'dialog.assets_damaged.title': {'en': 'Plugin file is damaged', 'ru': 'Файл плагина повреждён'},
    'dialog.load_crash.message': {'en': "tg-streaks failed to load at stage '{stage}'.\n\nA crash report has been copied to the clipboard — you can send it to the plugin's chat.", 'ru': 'Не удалось загрузить tg-streaks на этапе «{stage}».\n\nОтчёт скопирован в буфер обмена — вы можете отправить его в чат плагина.'},
    'dialog.load_crash.ok': {'en': 'OK', 'ru': 'Ок'},
    'dialog.load_crash.open_chat': {'en': 'Open chat', 'ru': 'Открыть чат'},
    'dialog.load_crash.title': {'en': 'Plugin load failed', 'ru': 'Не удалось загрузить плагин'},
    'dialog.update_restart.message': {'en': 'Streaks was updated from {previous} to {current}. Restart the client to finish the update.', 'ru': 'Streaks обновлён с {previous} до {current}. Перезапустите клиент, чтобы завершить обновление.'},
    'dialog.update_restart.restart': {'en': 'Restart', 'ru': 'Перезапустить'},
    'dialog.update_restart.title': {'en': 'Client restart required', 'ru': 'Требуется перезапуск клиента'},
}

I18N_STRINGS: dict[str, dict[str, str]] = {
    **I18N_STATUS,
    **I18N_DIALOGS,
}

# fmt: on


class EmbeddedAssetError(Exception):
    """Ошибка связанная с отсутствием или повреждением ассетов"""


class EmbeddedAssets:
    """Utility класс отвечающий за чтение встроенных в файл плагина ассетов"""

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin

    def read(self, block: tuple[str, str], label: str) -> bytes:
        begin, end = block
        path = self._source_path()

        if path is None:
            raise EmbeddedAssetError(f"plugin source not found for {label}")

        payload = bytearray()
        decompressor = lzma.LZMADecompressor()
        collecting = False
        completed = False

        try:
            with open(path, "r", encoding="utf-8") as source:
                for line in source:
                    stripped = line.strip()

                    if not collecting:
                        collecting = stripped == begin
                        continue

                    if stripped == end:
                        completed = True
                        break

                    if stripped.startswith("#"):
                        chunk = base64.b64decode(stripped[1:].strip())
                        payload += decompressor.decompress(chunk)
        except (OSError, EOFError, ValueError, lzma.LZMAError) as e:
            raise EmbeddedAssetError(f"failed to decode embedded {label}: {e}") from e

        if completed and not decompressor.eof:
            raise EmbeddedAssetError(f"embedded {label} is truncated")

        if not completed or not payload:
            raise EmbeddedAssetError(f"embedded {label} is missing or empty")

        return bytes(payload)

    def _source_path(self) -> Optional[str]:
        candidates: list[str] = []

        own_file = globals().get("__file__")
        if isinstance(own_file, str) and own_file:
            candidates.append(own_file)

        plugins_dir_getter = globals().get("get_plugins_dir")
        if callable(plugins_dir_getter):
            try:
                candidates.append(os.path.join(plugins_dir_getter(), f"{__id__}.py"))
            except Exception as e:
                self.plugin.log_exception("Failed to resolve plugins directory", e)

        for path in candidates:
            if os.path.isfile(path):
                return path

        return None


class JvmPluginBridge:
    """Загружает и инициализирует DEX в приложении (classes.dex)"""

    klass: Optional[Class]

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin
        self.klass = None

    def load(self):
        try:
            dex_data = self.plugin.assets.read(DEX_BLOCK, "classes.dex")
        except EmbeddedAssetError as e:
            self.plugin.log(f"Failed to read embedded DEX: {e}")
            self.plugin._show_assets_damaged_dialog("classes.dex")
            return

        self._load(dex_data)

    def _load(self, dex_data: bytes):
        class_path = "ru.n08i40k.streaks.Plugin"

        try:
            loader = InMemoryDexClassLoader(
                ByteBuffer.wrap(dex_data),
                ApplicationLoader.applicationContext.getClassLoader(),
            )
            self.klass = loader.loadClass(String(class_path))
        except Exception as e:
            self.plugin.log_exception("Failed to load DEX", e)


class ZipResourcesBridge:
    """Распаковывает архив с медиа-файлами (resources.zip)"""

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin
        self.cache_dir = get_plugin_cache_dir("plugins_resources_cache")
        os.makedirs(self.cache_dir, exist_ok=True)
        self.resources_root = os.path.join(self.cache_dir, "resources")
        self.zip_path = os.path.join(self.cache_dir, f"{__id__}-resources.zip")
        self.stamp_path = os.path.join(self.cache_dir, "resources.sha256")
        self.lock_path = os.path.join(self.cache_dir, "resources.lock")

    def load(self) -> Optional[str]:
        try:
            zip_data = self.plugin.assets.read(RESOURCES_BLOCK, "resources.zip")
        except EmbeddedAssetError as e:
            self.plugin.log(f"Failed to read embedded resources ZIP: {e}")
            self.plugin._show_assets_damaged_dialog("resources.zip")
            return None

        expected_sha256 = _sha256_hex(zip_data)

        if self._is_extracted(expected_sha256):
            return self.resources_root

        self.plugin.log("Embedded resources are not unpacked yet. Extracting...")

        lock_fd = self._acquire_lock()
        try:
            # another process may have won the race while we waited for the lock
            if not self._is_extracted(expected_sha256):
                self._extract_zip(zip_data, expected_sha256)
        finally:
            self._release_lock(lock_fd)

        if not self._is_extracted(expected_sha256):
            self.plugin._show_error(self.plugin._t("status.error.assets.unpack_failed"))
            return None

        return self.resources_root

    def _is_extracted(self, expected_sha256: str) -> bool:
        if not os.path.isdir(self.resources_root):
            return False

        try:
            with open(self.stamp_path, "r", encoding="utf-8") as f:
                return f.read().strip() == expected_sha256
        except OSError:
            return False

    def _acquire_lock(self) -> int:
        lock_fd = os.open(self.lock_path, os.O_CREAT | os.O_RDWR, 0o666)

        try:
            fcntl.flock(lock_fd, fcntl.LOCK_EX)
            return lock_fd
        except Exception:
            os.close(lock_fd)
            raise

    def _release_lock(self, lock_fd: int):
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        finally:
            os.close(lock_fd)

    def _extract_zip(self, zip_data: bytes, expected_sha256: str):
        staging_root = os.path.join(self.cache_dir, "resources-staging")

        if os.path.isdir(staging_root):
            shutil.rmtree(staging_root)

        os.makedirs(staging_root, exist_ok=True)

        try:
            with open(self.zip_path, "wb") as f:
                f.write(zip_data)

            with zipfile.ZipFile(self.zip_path) as zip_file:
                for member in zip_file.namelist():
                    normalized = member.replace("\\", "/")
                    if not normalized.startswith("resources/"):
                        raise RuntimeError(f"Unexpected ZIP entry: {member}")

                    target_path = os.path.abspath(
                        os.path.join(staging_root, normalized)
                    )
                    if not target_path.startswith(
                        os.path.abspath(staging_root) + os.sep
                    ):
                        raise RuntimeError(f"Unsafe ZIP entry: {member}")

                zip_file.extractall(staging_root)

            extracted_root = os.path.join(staging_root, "resources")
            if not os.path.isdir(extracted_root):
                raise RuntimeError("Resources ZIP does not contain resources/ root")

            if os.path.exists(self.stamp_path):
                os.remove(self.stamp_path)

            if os.path.isdir(self.resources_root):
                shutil.rmtree(self.resources_root)

            os.replace(extracted_root, self.resources_root)

            with open(self.stamp_path, "w", encoding="utf-8") as f:
                f.write(expected_sha256)

            self.plugin.log("Embedded resources extracted successfully")
        except Exception as e:
            self.plugin.log_exception("Failed to extract embedded resources", e)
        finally:
            if os.path.isdir(staging_root):
                shutil.rmtree(staging_root)

            if os.path.exists(self.zip_path):
                os.remove(self.zip_path)


class TgStreaksPlugin(BasePlugin):
    """Основной класс плагина"""

    _full_load_lock = threading.Lock()
    _eject_lock = threading.Lock()

    def log(self, message: Any):
        text = str(message)
        super().log(text)

        if getattr(self, "_load_logging_active", False):
            self._load_log_buffer.append(text)

        try:
            Log.i(cast("String", LOGCAT_TAG), cast("String", text))
        except Exception:
            pass

    def log_exception(self, message: str, exception: BaseException):
        def _log_e(text: str):
            if DEBUG_MODE:
                try:
                    Log.e(cast("String", LOGCAT_TAG), cast("String", text))
                except Exception:
                    pass

        text = f"{message}: {exception}"
        self.log(text)
        _log_e(text)

        for chunk in traceback.format_exception(
            type(exception),
            exception,
            exception.__traceback__,
        ):
            for line in chunk.rstrip().splitlines():
                if line:
                    self.log(line)
                    _log_e(line)

    def _show_info(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_info(message))

    def _show_error(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_error(message))

    def _show_success(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_success(message))

    def _get_last_loaded_version(self) -> str:
        previous_version = ""

        try:
            previous_version = str(self.get_setting(SETTING_LAST_LOADED_VERSION, ""))
        except Exception as e:
            self.log_exception("Failed to read last loaded plugin version", e)

        return previous_version

    def _persist_current_loaded_version(self):
        try:
            self.set_setting(SETTING_LAST_LOADED_VERSION, __version__)
        except Exception as e:
            self.log_exception("Failed to persist last loaded plugin version", e)

    def _should_pause_full_load_for_update(self) -> bool:
        previous_version = self._get_last_loaded_version()

        if len(previous_version) == 0 or previous_version == __version__:
            self._persist_current_loaded_version()
            return False

        if not DEBUG_MODE and _is_version_older(__version__, previous_version):
            return False

        self._show_update_restart_dialog(previous_version)
        return True

    def _show_assets_damaged_dialog(self, filename: str):
        self.log(f"Embedded {filename} is unusable: plugin load aborted")

        def show():
            try:
                fragment = get_last_fragment()
            except Exception:
                fragment = None

            message = self._t("dialog.assets_damaged.message", filename=filename)

            if fragment is None:
                self._show_error(message)
                return

            try:
                fragment.showDialog(
                    AlertDialog.Builder(fragment.getContext())
                    .setTitle(String(self._t("dialog.assets_damaged.title")))
                    .setMessage(String(message))
                    .setPositiveButton(
                        String(self._t("dialog.assets_damaged.ok")),
                        None,  # ty:ignore[invalid-argument-type]
                    )
                    .create()
                )
            except Exception as e:
                self.log_exception("Failed to show damaged assets dialog", e)
                self._show_error(message)

        run_on_ui_thread(show)

    def _show_update_restart_dialog(self, previous_version: str):
        def show():
            try:
                fragment = get_last_fragment()
            except Exception:
                fragment = None

            if fragment is None:
                self.log("Update restart dialog deferred: UI context is unavailable")
                self._schedule_update_restart_dialog_retry(previous_version)
                return

            self_outer = self

            class RestartClickListener(
                dynamic_proxy(AlertDialog.OnButtonClickListener)
            ):
                def onClick(self, _dialog: AlertDialog, _which: int) -> None:  # ty: ignore[invalid-method-override]
                    self_outer._persist_current_loaded_version()
                    self_outer._restart_client()

            class DismissListener(dynamic_proxy(DialogInterface.OnDismissListener)):
                def onDismiss(self, arg0) -> None:
                    self_outer._persist_current_loaded_version()
                    self_outer._restart_client()

            try:
                fragment.showDialog(
                    AlertDialog.Builder(fragment.getContext())
                    .setTitle(String(self._t("dialog.update_restart.title")))
                    .setMessage(
                        String(
                            self._t(
                                "dialog.update_restart.message",
                                previous=previous_version,
                                current=__version__,
                            )
                        )
                    )
                    .setPositiveButton(
                        String(self._t("dialog.update_restart.restart")),
                        RestartClickListener(),
                    )
                    .setOnDismissListener(DismissListener())
                    .setOnPreDismissListener(DismissListener())
                    .create()
                )
            except Exception as e:
                self.log_exception("Failed to show update restart dialog", e)
                self._show_info(
                    self._t(
                        "dialog.update_restart.message",
                        previous=previous_version,
                        current=__version__,
                    )
                )
                self._schedule_update_restart_dialog_retry(previous_version)

        run_on_ui_thread(show)

    def _schedule_update_restart_dialog_retry(self, previous_version: str):
        timer = threading.Timer(
            1.0,
            lambda: self._show_update_restart_dialog(previous_version),
        )
        timer.daemon = True
        timer.start()

    def _continue_plugin_load(self) -> threading.Thread:
        thread = threading.Thread(
            target=self._run_plugin_load,
            name="tg-streaks-continue-plugin-load",
            daemon=True,
        )
        thread.start()

        return thread

    def _reset_load_log_buffer(self):
        self._load_log_buffer: list[str] = []
        self._load_logging_active = True

    def _stop_load_logging(self):
        self._load_logging_active = False
        self._load_log_buffer = []

    def _handle_load_failure(self, stage: str, exception: BaseException):
        self.log_exception(f"Plugin load failed ({stage})", exception)

        logs = "\n".join(getattr(self, "_load_log_buffer", []))
        report = (
            f"#load_crash\n\n"
            f"Stage: `{stage}`\n"
            f"Plugin version: `{__version__}`\n\n"
            f"Error:\n```\n{exception}\n```\n\n"
            f"Log:\n```\n{logs}\n```"
        )

        try:
            copy_to_clipboard(report)
        except Exception as e:
            self.log_exception("Failed to copy load-crash report to clipboard", e)

        self._show_load_crash_dialog(stage)

    def _show_load_crash_dialog(self, stage: str):
        def show():
            try:
                fragment = get_last_fragment()
            except Exception:
                fragment = None

            message = self._t("dialog.load_crash.message", stage=stage)

            if fragment is None:
                self._show_error(message)
                return

            self_outer = self

            class OpenChatClickListener(
                dynamic_proxy(AlertDialog.OnButtonClickListener)
            ):
                def onClick(self, _dialog: AlertDialog, _which: int) -> None:  # ty: ignore[invalid-method-override]
                    self_outer._open_telegram_url(PLUGIN_CHAT_TG_URL)

            try:
                fragment.showDialog(
                    AlertDialog.Builder(fragment.getContext())
                    .setTitle(String(self._t("dialog.load_crash.title")))
                    .setMessage(String(message))
                    .setPositiveButton(
                        String(self._t("dialog.load_crash.open_chat")),
                        OpenChatClickListener(),
                    )
                    .setNegativeButton(
                        String(self._t("dialog.load_crash.ok")),
                        None,  # ty:ignore[invalid-argument-type]
                    )
                    .create()
                )
            except Exception as e:
                self.log_exception("Failed to show load crash dialog", e)
                self._show_error(message)

        run_on_ui_thread(show)

    def _restart_client(self):
        def restart():
            try:
                Process.killProcess(Process.myPid())
            except Exception as e:
                self.log_exception("Failed to restart client", e)
                self._show_error(self._t("status.error.update.restart_failed"))
                self.on_plugin_load()

        run_on_ui_thread(restart)

    def _get_app_language_code(self) -> str:
        def safe_call(fn):
            try:
                return fn()
            except Exception:
                return None

        lang_code = None

        lc = safe_call(LocaleController.getInstance)
        if lc is not None:
            info = safe_call(lc.getCurrentLocaleInfo)
            if info is not None:
                has_base_lang = safe_call(info.hasBaseLang)
                if has_base_lang:
                    raw = safe_call(lambda: info.baseLangCode)
                else:
                    raw = safe_call(info.getLangCode) or safe_call(
                        lambda: info.shortName
                    )
                if raw:
                    lang_code = str(raw)

            if not lang_code:
                locale = safe_call(lc.getCurrentLocale)
                if locale is not None:
                    raw = safe_call(locale.getLanguage)
                    if raw:
                        lang_code = str(raw)

        if not lang_code:
            return "en"

        normalized = lang_code.strip().lower().replace("-", "_")
        code = normalized.split("_", 1)[0]
        return code if code in VALID_ISO_LANGUAGES else "en"

    def _t(self, key: str, **kwargs: Any) -> str:
        values = I18N_STRINGS.get(key, None)
        if values is None:
            text = key
        else:
            lang = self._get_app_language_code()
            text = values.get(lang) or values.get("en") or key

        try:
            return str(text).format(**kwargs)
        except Exception:
            return str(text)

    def _resolve_popup_context(self):
        try:
            fragment = get_last_fragment()
        except Exception:
            fragment = None

        context = None

        if fragment is not None:
            try:
                context = fragment.getParentActivity()
            except Exception:
                context = None

            if context is None:
                try:
                    context = fragment.getContext()
                except Exception:
                    context = None

        if context is None:
            try:
                context = ApplicationLoader.applicationContext
            except Exception:
                context = None

        return context

    def _open_telegram_url(self, url: str):
        def open_url():
            try:
                context = self._resolve_popup_context()
                if context is None:
                    raise RuntimeError("no context")

                intent = Intent()
                intent.setAction(String(Intent.ACTION_VIEW))
                intent.setData(Uri.parse(String(url)))
                intent.setPackage(String(context.getPackageName()))
                intent.addFlags(int(Intent.FLAG_ACTIVITY_NEW_TASK))
                context.startActivity(intent)
            except Exception as e:
                self.log_exception(f"Failed to open Telegram url {url}", e)
                self._show_error(self._t("status.error.update.open_link_failed"))

        run_on_ui_thread(open_url)

    def _prepare_jvm_plugin(self) -> bool:
        self.jvm_plugin = JvmPluginBridge(self)
        self.jvm_plugin.load()

        if self.jvm_plugin.klass is None:
            return False

        self.resources_root = self.resources_bridge.load()
        return self.resources_root is not None

    def _inject_jvm_plugin(self) -> bool:
        try:
            build_date = self.jvm_plugin.klass.getDeclaredMethod(  # ty:ignore[possibly-missing-attribute]
                String("getBuildDate")
            ).invoke(None)
            self.log(f"Loading JVM plugin {build_date}")
        except Exception as e:
            self.log_exception("Failed to infer JVM plugin version", e)

        try:
            ref = self

            class Logger(dynamic_proxy(ValueCallback)):
                def onReceiveValue(self, arg0):
                    ref.log(str(arg0))

            class ReloadRequest(dynamic_proxy(Runnable)):
                def run(self):
                    ref._reload_plugin()

            injected = self.jvm_plugin.klass.getDeclaredMethod(  # ty:ignore[possibly-missing-attribute]
                String("inject"),
                String.getClass(),
                ValueCallback.getClass(),
                String.getClass(),
                Boolean.TYPE,
                Runnable.getClass(),
            ).invoke(
                None,
                String(__version__),
                Logger(),
                String(self.resources_root),
                Boolean(DEBUG_MODE),
                ReloadRequest(),
            )

            if not injected:
                self.log("JVM plugin refused to inject")
                return False

            self.log("JVM plugin injected successfully")
        except Exception as e:
            self._handle_load_failure("inject", e)
            self.on_plugin_eject()
            return False

        return True

    def _run_plugin_load(self):
        with self._full_load_lock:
            if getattr(self, "_full_load_started", False):
                return
            self._full_load_started = True

        try:
            if not self._prepare_jvm_plugin():
                with self._full_load_lock:
                    self._full_load_started = False
                return

            if not self._inject_jvm_plugin():
                return
        except BaseException as e:
            self._handle_load_failure("plugin load", e)
            return

        self._stop_load_logging()

    def _reload_plugin(self):
        self.log("Reload requested by JVM plugin")

        try:
            self.on_plugin_unload()
        except BaseException as e:
            self.log_exception("Failed during plugin unload before reload", e)

        self.on_plugin_load(allow_update_pause=False)

    def on_plugin_load(
        self, allow_update_pause: bool = True
    ) -> threading.Thread | None:
        self._reset_load_log_buffer()
        self._ejected = False

        try:
            self.assets = EmbeddedAssets(self)
            self.resources_bridge = ZipResourcesBridge(self)
            self._full_load_started = False

            if allow_update_pause and self._should_pause_full_load_for_update():
                return None

            return self._continue_plugin_load()
        except BaseException as e:
            self._handle_load_failure("plugin load", e)
            return None

    def on_plugin_unload(self):
        jvm_plugin = getattr(self, "jvm_plugin", None)

        if jvm_plugin is None or jvm_plugin.klass is None:
            return

        try:
            jvm_plugin.klass.getDeclaredMethod(String("eject")).invoke(None)
            self.log("JVM plugin ejected successfully")
        except Exception as e:
            self.log_exception("Failed to eject JVM plugin", e)

        jvm_plugin.klass = None

    def on_plugin_eject(self):
        # блокирует конкурентные вызовы eject
        with self._eject_lock:
            if getattr(self, "_ejected", False):
                return
            self._ejected = True

        self.log("JVM plugin instance lost: ejected by a concurrent reload")

        jvm_plugin = getattr(self, "jvm_plugin", None)
        if jvm_plugin is not None:
            jvm_plugin.klass = None


# === EMDEDDED DEX BEGIN ===
# === EMDEDDED DEX END ===
# === EMDEDDED RESOURCES BEGIN ===
# === EMDEDDED RESOURCES END ===
