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

import requests
from java import dynamic_proxy, jarray, jbyte
from android.content import Intent
from android.content import DialogInterface
from android.net import Uri
from android.os import Environment, Process
from android.util import Log
from android.webkit import ValueCallback
from android_utils import copy_to_clipboard, run_on_ui_thread
from base_plugin import BasePlugin, MenuItemData, MenuItemType, MethodHook
from client_utils import get_last_fragment
from dalvik.system import InMemoryDexClassLoader
from java.lang import Boolean, Class, Integer, Long, String
from java.nio import ByteBuffer
from java.util import Locale
from org.telegram.messenger import ApplicationLoader, LocaleController
from org.telegram.messenger import R as R_tg
from org.telegram.ui.ActionBar import AlertDialog
from typing_extensions import Any
from ui.bulletin import BulletinHelper
from ui.settings import Divider, Header, Selector, Switch, Text

# fmt: off

__id__ = "tg-streaks"
__name__ = "Streaks"
__description__ = "Аналог стриков TikTok для Telegram"
__author__ = "@n08i40k_extera & @RoflPlugins"
__version__ = "2.19.1"
__icon__ = "tiktok_streak/4"
__min_version__ = "12.1.1"

# Core constants

DEBUG_MODE = False
LOGCAT_TAG = __id__

# Helper constants

REPO_OWNER = "n08i40k"
REPO_NAME = __id__

# Resource hashes
DEX_BLOCK = ("# === EMDEDDED DEX BEGIN ===", "# === EMDEDDED DEX END ===")
RESOURCES_BLOCK = ("# === EMDEDDED RESOURCES BEGIN ===", "# === EMDEDDED RESOURCES END ===")

# Plugin official resource links

PLUGIN_UPDATE_TG_URL = "tg://resolve?domain=n08i40k_extera&post=3"
PLUGIN_CHAT_TG_URL = "tg://resolve?domain=n08i40k_extera_chat"
PLUGIN_UPDATE_API_URL = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest"
PLUGIN_DOCS_URL = f"https://{REPO_OWNER}.github.io/{REPO_NAME}/"

# Other constants

UPDATE_CHECK_TIMEOUT_SECONDS = 6
SETTING_UPDATE_CHECK_ENABLED = "update_check_enabled"
SETTING_LAST_LOADED_VERSION = "last_loaded_version"
SETTING_PET_FAB_SIZE_INDEX = "pet_fab_size_index"
SETTING_AUTO_STREAK_CREATION_ENABLED = "auto_streak_creation_enabled"
PET_FAB_SIZE_OPTIONS_DP = (64, 80, 96, 112, 128)
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


I18N_SETTINGS: dict[str, dict[str, str]] = {
    "settings.backups.description": {
        "en": "Backups are saved to Downloads/tg-streaks. Restore replaces the current database.",
        "ru": "Бэкапы лежат в Downloads/tg-streaks. Восстановление заменяет текущую базу.",
    },
    "settings.backups.export.title": {"en": "Create backup", "ru": "Создать бэкап"},
    "settings.backups.reset_database.title": {
        "en": "Reset plugin database",
        "ru": "Сбросить базу плагина",
    },
    "settings.backups.restore.title": {
        "en": "Restore backup",
        "ru": "Восстановить бэкап",
    },
    "settings.backups.title": {"en": "Backups", "ru": "Бэкапы"},
    "settings.help.docs.description": {
        "en": "Open the plugin user guide in your browser.",
        "ru": "Открыть руководство пользователя в браузере.",
    },
    "settings.help.docs.title": {"en": "Documentation", "ru": "Документация"},
    "settings.help.title": {"en": "Help", "ru": "Справка"},
    "settings.pet_button.size.description": {
        "en": "Floating chat button size.",
        "ru": "Размер плавающей кнопки в чате.",
    },
    "settings.pet_button.size.title": {"en": "Button size", "ru": "Размер кнопки"},
    "settings.pet_button.title": {"en": "Streak pet", "ru": "Серийчик"},
    "settings.streak_tools.auto_create.description": {
        "en": "Streaks start on their own while chatting. When off, a streak appears only after a rebuild.",
        "ru": "Стрики создаются сами во время переписки. Если выключено, стрик появится только после пересчёта.",
    },
    "settings.streak_tools.auto_create.title": {
        "en": "Automatic streak creation",
        "ru": "Автосоздание стриков",
    },
    "settings.streak_tools.emoji_packs.title": {
        "en": "Emoji packs",
        "ru": "Эмодзи-паки",
    },
    "settings.streak_tools.rebuild_all_chats.description": {
        "en": "Only user DMs are checked. Bots and groups are skipped.",
        "ru": "Только лички с пользователями. Боты и группы пропускаются.",
    },
    "settings.streak_tools.rebuild_all_chats.title": {
        "en": "Private chats rebuild",
        "ru": "Пересчёт стриков в личках",
    },
    "settings.streak_tools.title": {"en": "Streak", "ru": "Стрик"},
    "settings.updates.auto_check.description": {
        "en": "Checks GitHub releases on startup.",
        "ru": "Проверяет релизы GitHub при запуске.",
    },
    "settings.updates.auto_check.title": {
        "en": "Update checks",
        "ru": "Проверка обновлений",
    },
    "settings.updates.title": {"en": "Updates", "ru": "Обновления"},
}

I18N_STATUS: dict[str, dict[str, str]] = {
    "status.error.backup.apply_failed": {
        "en": "Backup restore failed: {reason}",
        "ru": "Не удалось восстановить бэкап: {reason}",
    },
    "status.error.backup.not_found": {
        "en": "No backups found",
        "ru": "Бэкапы не найдены",
    },
    "status.error.assets.unpack_failed": {
        "en": "Failed to unpack plugin resources",
        "ru": "Не удалось распаковать ресурсы плагина",
    },
    "status.error.chat.detect_current_failed": {
        "en": "Current chat not found",
        "ru": "Текущий чат не найден",
    },
    "status.error.database.delete_failed": {
        "en": "Database reset failed: {reason}",
        "ru": "Не удалось сбросить базу: {reason}",
    },
    "status.error.update.open_link_failed": {
        "en": "Couldn't open update link",
        "ru": "Не удалось открыть ссылку обновления",
    },
    "status.error.update.restart_failed": {
        "en": "Couldn't restart client",
        "ru": "Не удалось перезапустить клиент",
    },
    "status.success.backup.imported": {
        "en": "Backup restored: {name}",
        "ru": "Бэкап восстановлен: {name}",
    },
    "status.success.database.reset_started": {
        "en": "Database reset started",
        "ru": "Сброс базы запущен",
    },
}

I18N_MENU: dict[str, dict[str, str]] = {
    "menu.chat.control_panel.description": {
        "en": "Streak, pet, sync and time zone settings",
        "ru": "Настройки стрика, серийчика, синхронизации и часового пояса",
    },
    "menu.chat.control_panel.title": {"en": "Control panel", "ru": "Панель управления"},
    "menu.chat.restore_streak_exact.description": {
        "en": "Available anytime, but limited by 2 usages per chat",
        "ru": "Доступно в любое время, но с ограничением по 2 раза на чат",
    },
    "menu.chat.restore_streak_exact.title": {
        "en": "Streak restore menu",
        "ru": "Меню восстановление стрика",
    },
    "menu.debug.crash_plugin.description": {"en": "Test crash", "ru": "Тестовый краш"},
    "menu.debug.crash_plugin.title": {
        "en": "[DEBUG] Plugin crash",
        "ru": "[DEBUG] Краш плагина",
    },
    "menu.debug.create_streak.description": {
        "en": "Sets a 3-day streak in this chat",
        "ru": "Ставит стрик на 3 дня в этом чате",
    },
    "menu.debug.create_streak.title": {
        "en": "[DEBUG] 3-day streak",
        "ru": "[DEBUG] Стрик на 3 дня",
    },
    "menu.debug.delete_pet.description": {
        "en": "Deletes the streak pet from this chat",
        "ru": "Удаляет серийчика из этого чата",
    },
    "menu.debug.delete_pet.title": {
        "en": "[DEBUG] Streak pet delete",
        "ru": "[DEBUG] Удаление серийчика",
    },
    "menu.debug.delete_streak.description": {
        "en": "Deletes the streak from this chat",
        "ru": "Удаляет стрик из этого чата",
    },
    "menu.debug.delete_streak.title": {
        "en": "[DEBUG] Streak delete",
        "ru": "[DEBUG] Удаление стрика",
    },
    "menu.debug.freeze_streak.description": {
        "en": "Sets a frozen streak in this chat",
        "ru": "Ставит замороженный стрик в этом чате",
    },
    "menu.debug.freeze_streak.title": {
        "en": "[DEBUG] Streak freeze",
        "ru": "[DEBUG] Заморозка стрика",
    },
    "menu.debug.kill_streak.description": {
        "en": "Breaks the streak in this chat",
        "ru": "Прерывает стрик в этом чате",
    },
    "menu.debug.kill_streak.title": {
        "en": "[DEBUG] Streak break",
        "ru": "[DEBUG] Обрыв стрика",
    },
    "menu.debug.upgrade_streak.description": {
        "en": "Moves the streak to the next level",
        "ru": "Поднимает стрик до следующего уровня",
    },
    "menu.debug.upgrade_streak.title": {
        "en": "[DEBUG] Streak upgrade",
        "ru": "[DEBUG] Повышение стрика",
    },
}

I18N_DIALOGS: dict[str, dict[str, str]] = {
    "dialog.assets_damaged.message": {
        "en": "{filename} is missing from the plugin file or is damaged. Reinstall the plugin.",
        "ru": "{filename} отсутствует в файле плагина или повреждён. Переустановите плагин.",
    },
    "dialog.assets_damaged.ok": {"en": "OK", "ru": "Ок"},
    "dialog.assets_damaged.title": {
        "en": "Plugin file is damaged",
        "ru": "Файл плагина повреждён",
    },
    "dialog.backup_restore.browse": {
        "en": "Browse from device...",
        "ru": "Выбрать с устройства...",
    },
    "dialog.backup_restore.title": {"en": "Choose backup", "ru": "Выберите бэкап"},
    "dialog.downgrade.channel": {
        "en": "Updates channel",
        "ru": "Канал с обновлениями",
    },
    "dialog.downgrade.message": {
        "en": "Streaks was downgraded from {previous} to {current}. Old versions cannot work with data written by a newer one, so the plugin will not load and can crash the client. Install {previous} or newer from the updates channel.",
        "ru": "Streaks понижен с {previous} до {current}. Старая версия не умеет работать с данными новой, поэтому плагин не будет загружен и может крашить клиент. Установите {previous} или новее из канала с обновлениями.",
    },
    "dialog.downgrade.title": {
        "en": "Plugin version downgraded",
        "ru": "Версия плагина понижена",
    },
    "dialog.load_crash.message": {
        "en": "tg-streaks failed to load at stage '{stage}'.\n\nA crash report has been copied to the clipboard — you can send it to the plugin's chat.",
        "ru": "Не удалось загрузить tg-streaks на этапе «{stage}».\n\nОтчёт скопирован в буфер обмена — вы можете отправить его в чат плагина.",
    },
    "dialog.load_crash.ok": {"en": "OK", "ru": "Ок"},
    "dialog.load_crash.open_chat": {"en": "Open chat", "ru": "Открыть чат"},
    "dialog.load_crash.title": {
        "en": "Plugin load failed",
        "ru": "Не удалось загрузить плагин",
    },
    "dialog.update_restart.message": {
        "en": "Streaks was updated from {previous} to {current}. Restart the client to finish the update.",
        "ru": "Streaks обновлён с {previous} до {current}. Перезапустите клиент, чтобы завершить обновление.",
    },
    "dialog.update_restart.restart": {"en": "Restart", "ru": "Перезапустить"},
    "dialog.update_restart.title": {
        "en": "Client restart required",
        "ru": "Требуется перезапуск клиента",
    },
}

I18N_UPDATE: dict[str, dict[str, str]] = {
    "update.available.action": {"en": "Update", "ru": "Обновить"},
    "update.available.message": {
        "en": "Update available: {current} -> {latest}",
        "ru": "Есть обновление: {current} -> {latest}",
    },
}

I18N_STRINGS: dict[str, dict[str, str]] = {
    **I18N_SETTINGS,
    **I18N_STATUS,
    **I18N_MENU,
    **I18N_DIALOGS,
    **I18N_UPDATE,
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


class BadgesSdk:
    """Загружает движок Badges SDK через встроенный лоадер"""

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin
        self.loader: Optional[Any] = None

        loader_class = globals().get("BadgesSdkLoader")

        if loader_class is None:
            plugin.log("Badges SDK loader is not embedded into this build")
            return

        self.loader = loader_class(__id__, logger=plugin.log)

    def remove_standalone_plugin(self):
        if self.loader is None:
            return

        try:
            self.loader.remove_standalone_plugin()
        except Exception as e:
            self.plugin.log_exception("Failed to remove the standalone Badges SDK", e)

    def load(self):
        if self.loader is None:
            return

        try:
            result = self.loader.load()
        except Exception as e:
            self.plugin.log_exception("Failed to load Badges SDK", e)
            return

        if not result.usable:
            self.plugin.log(f"Badges SDK is unavailable: {result}")

    def unload(self):
        if self.loader is None:
            return

        try:
            self.loader.unload()
        except Exception as e:
            self.plugin.log_exception("Failed to unload Badges SDK", e)


class ChatContextMenu:
    """Отвечает за регистрацию кнопок в контекстном меню чата"""

    CONTROL_MENU = "controlMenu"
    RESTORE_EXACT = "restoreExact"

    DEBUG_CREATE = "debug.create"
    DEBUG_UPGRADE = "debug.upgrade"
    DEBUG_FREEZE = "debug.freeze"
    DEBUG_KILL = "debug.kill"
    DEBUG_DELETE = "debug.delete"
    DEBUG_DELETE_PET = "debug.deletePet"
    DEBUG_CRASH = "debug.crash"

    MENU_PAYLOAD_DIALOG_KEYS = (
        "dialog_id",
        "dialogId",
        "peer_id",
        "peerId",
        "chat_id",
        "chatId",
        "user_id",
        "userId",
    )
    MENU_PAYLOAD_FRAGMENT_KEYS = (
        "chatActivity",
        "fragment",
        "chatFragment",
        "target",
        "object",
    )

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin
        self._item_ids: dict[str, str] = {}
        self._callbacks: dict[str, Any] = {}

    @classmethod
    def _menu_items(cls) -> tuple[dict[str, Any], ...]:
        return (
            {
                "key": cls.CONTROL_MENU,
                "text_key": "menu.chat.control_panel.title",
                "subtext_key": "menu.chat.control_panel.description",
                "icon": "msg_settings",
                "priority": 1001,
            },
            {
                "key": cls.RESTORE_EXACT,
                "text_key": "menu.chat.restore_streak_exact.title",
                "subtext_key": "menu.chat.restore_streak_exact.description",
                "icon": "msg_reactions",
                "priority": 999,
            },
            {
                "key": cls.DEBUG_CREATE,
                "text_key": "menu.debug.create_streak.title",
                "subtext_key": "menu.debug.create_streak.description",
                "priority": 993,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_UPGRADE,
                "text_key": "menu.debug.upgrade_streak.title",
                "subtext_key": "menu.debug.upgrade_streak.description",
                "priority": 992,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_FREEZE,
                "text_key": "menu.debug.freeze_streak.title",
                "subtext_key": "menu.debug.freeze_streak.description",
                "priority": 991,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_KILL,
                "text_key": "menu.debug.kill_streak.title",
                "subtext_key": "menu.debug.kill_streak.description",
                "priority": 990,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_DELETE,
                "text_key": "menu.debug.delete_streak.title",
                "subtext_key": "menu.debug.delete_streak.description",
                "priority": 989,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_DELETE_PET,
                "text_key": "menu.debug.delete_pet.title",
                "subtext_key": "menu.debug.delete_pet.description",
                "priority": 988,
                "debug_only": True,
            },
            {
                "key": cls.DEBUG_CRASH,
                "text_key": "menu.debug.crash_plugin.title",
                "subtext_key": "menu.debug.crash_plugin.description",
                "priority": 987,
                "debug_only": True,
            },
        )

    def register(self):
        self.unregister()

        for item in self._menu_items():
            if item.get("debug_only", False) and not DEBUG_MODE:
                continue

            key = str(item["key"])

            try:
                menu_item_args = {
                    "menu_type": MenuItemType.CHAT_ACTION_MENU,
                    "text": self.plugin._t(str(item["text_key"])),
                    "subtext": self.plugin._t(str(item["subtext_key"])),
                    "on_click": lambda payload, button=key: self._on_click(
                        button, payload
                    ),
                    "priority": int(item["priority"]),
                }

                if "icon" in item:
                    menu_item_args["icon"] = item["icon"]

                item_id = self.plugin.add_menu_item(
                    MenuItemData(
                        **menu_item_args,
                    )
                )

                self._item_ids[key] = str(item_id)
            except Exception as e:
                self.plugin.log_exception(
                    f"Failed to register chat context menu item {key}",
                    e,
                )

    def unregister(self):
        for key, item_id in tuple(self._item_ids.items()):
            try:
                self.plugin.remove_menu_item(item_id)
            except Exception as e:
                self.plugin.log_exception(
                    f"Failed to remove chat context menu item {key}",
                    e,
                )

        self._item_ids.clear()
        self._callbacks.clear()

    def _on_click(self, key: str, payload: Any):
        dialog_id = self._extract_dialog_id_from_payload(payload)
        if dialog_id is None:
            self.plugin.log(
                f"Chat context menu click payload missing dialog id for {key}: {payload}"
            )
            self.plugin._show_error(
                self.plugin._t("status.error.chat.detect_current_failed")
            )
            return

        try:
            self._invoke_callback(String(key), Long(dialog_id))
        except Exception as e:
            self.plugin.log_exception(
                f"Failed to execute chat context menu callback {key} for {dialog_id}",
                e,
            )

    def _invoke_callback(self, key: String, value: Long):
        if self.plugin.jvm_plugin.klass is None:
            self.plugin.log(
                f"Chat context menu callback {key} is unavailable: JVM plugin is not loaded"
            )
            return

        try:
            self.plugin.jvm_plugin.klass.getDeclaredMethod(
                String("invokeChatContextMenuCallback"),
                String.getClass(),
                Long.TYPE,
            ).invoke(None, key, value)
        except Exception as e:
            self.plugin.log_exception(
                f"Failed to resolve chat context menu callback {key}",
                e,
            )

    def _extract_dialog_id_from_payload(self, payload: Any) -> Optional[int]:
        if payload is None:
            return None

        def parse_dialog_id(value: Any) -> Optional[int]:
            if value is None:
                return None
            try:
                return int(value) or None
            except Exception:
                return None

        payload_getter = getattr(payload, "get", None)
        if payload_getter is not None:
            for key in self.MENU_PAYLOAD_DIALOG_KEYS:
                if (dialog_id := parse_dialog_id(payload_getter(key))) is not None:
                    return dialog_id

            for key in self.MENU_PAYLOAD_FRAGMENT_KEYS:
                value = payload_getter(key)
                if value is None:
                    continue
                try:
                    if (dialog_id := parse_dialog_id(value.getDialogId())) is not None:
                        return dialog_id
                except Exception:
                    pass

        try:
            return parse_dialog_id(payload.getDialogId())
        except Exception:
            return None


class SettingsActions:
    """Отвечает за регистрацию элементов в настройках плагина"""

    REBUILD_ALL = "rebuildAllPrivateChats"
    EXPORT_BACKUP_NOW = "exportBackupNow"
    DELETE_DB_AND_RELOAD = "deleteDbAndReload"
    OPEN_EMOJI_PACKS = "openEmojiPacks"

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin

    def build_settings(self) -> list[Any]:
        return [
            Header(text=self.plugin._t("settings.pet_button.title")),
            Selector(
                key=SETTING_PET_FAB_SIZE_INDEX,
                text=self.plugin._t("settings.pet_button.size.title"),
                default=self.plugin._get_pet_fab_size_index(),
                items=[f"{size} dp" for size in PET_FAB_SIZE_OPTIONS_DP],
                icon="msg_customize",
                on_change=lambda value: self.plugin._on_pet_fab_size_changed(value),
                link_alias="pet-button-size",
            ),
            Divider(text=self.plugin._t("settings.pet_button.size.description")),
            Header(text=self.plugin._t("settings.streak_tools.title")),
            Switch(
                key=SETTING_AUTO_STREAK_CREATION_ENABLED,
                text=self.plugin._t("settings.streak_tools.auto_create.title"),
                default=self.plugin._is_auto_streak_creation_enabled(),
                subtext=self.plugin._t("settings.streak_tools.auto_create.description"),
                icon="msg_add",
                on_change=lambda value: self.plugin._on_auto_streak_creation_changed(
                    value
                ),
                link_alias="auto-create",
            ),
            Text(
                text=self.plugin._t("settings.streak_tools.emoji_packs.title"),
                icon="msg_emoji_smiles",
                on_click=lambda _: self._on_click(self.OPEN_EMOJI_PACKS),
                link_alias="emoji-packs",
            ),
            Text(
                text=self.plugin._t("settings.streak_tools.rebuild_all_chats.title"),
                icon="msg_retry",
                on_click=lambda _: self._on_click(self.REBUILD_ALL),
                link_alias="rebuild-all",
            ),
            Divider(
                text=self.plugin._t(
                    "settings.streak_tools.rebuild_all_chats.description"
                )
            ),
            Header(text=self.plugin._t("settings.backups.title")),
            Text(
                text=self.plugin._t("settings.backups.export.title"),
                icon="msg_download",
                on_click=lambda _: self._on_click(self.EXPORT_BACKUP_NOW),
                link_alias="backup-create",
            ),
            Text(
                text=self.plugin._t("settings.backups.restore.title"),
                icon="msg_reset",
                on_click=lambda _: self.plugin._show_restore_backup_file_dialog(),
                link_alias="backup-restore",
            ),
            Text(
                text=self.plugin._t("settings.backups.reset_database.title"),
                icon="msg_delete",
                on_click=lambda _: self.plugin._schedule_database_reset_reinitialize(),
                link_alias="backup-reset",
            ),
            Divider(text=self.plugin._t("settings.backups.description")),
        ]

    def _on_click(self, key: str):
        try:
            self._invoke_callback(String(key))
        except Exception as e:
            self.plugin.log_exception(
                f"Failed to execute settings callback {key}",
                e,
            )

    def _invoke_callback(self, key: String):
        if self.plugin.jvm_plugin.klass is None:
            self.plugin.log(
                f"Settings callback {key} is unavailable: JVM plugin is not loaded"
            )
            return

        try:
            self.plugin.jvm_plugin.klass.getDeclaredMethod(
                String("invokeSettingsActionCallback"),
                String.getClass(),
            ).invoke(None, key)
        except Exception as e:
            self.plugin.log_exception(
                f"Failed to resolve settings callback {key}",
                e,
            )


class PluginUpdateChecker:
    """
    Проверяет обновления плагина через GitHub API и показывает
    уведомлении при наличии новой версии
    """

    def __init__(self, plugin: "TgStreaksPlugin"):
        self.plugin = plugin
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._inflight = False

    def is_enabled(self) -> bool:
        return self.plugin._is_update_check_enabled()

    def set_enabled(self, enabled: bool):
        enabled = bool(enabled)

        try:
            self.plugin.set_setting(SETTING_UPDATE_CHECK_ENABLED, enabled)
        except Exception as e:
            self.plugin.log_exception("Failed to persist update check setting", e)

        if enabled:
            self.start()
            return

        self.stop()

    def stop(self):
        self._stop.set()

    def start(self):
        if not self.is_enabled():
            self.plugin.log("Update check skipped: disabled in plugin settings")
            return

        with self._lock:
            if self._inflight:
                return
            self._inflight = True

        self._stop.clear()

        def worker():
            try:
                latest_tag = self._fetch_latest_release_tag()
                if latest_tag is None:
                    self.plugin.log(
                        "Update check skipped: latest release tag_name is missing"
                    )
                    return

                latest_version = self._normalize_version_tag(latest_tag)
                current_version = self._normalize_version_tag(__version__)

                if not latest_version:
                    self.plugin.log(
                        "Update check skipped: latest release version is empty"
                    )
                    return

                if latest_version == current_version:
                    self.plugin.log(f"Plugin is up to date: {current_version}")
                    return

                if self._stop.is_set():
                    return

                self.plugin.log(
                    f"Plugin update available: current={__version__}, latest={latest_tag}"
                )
                self._show_update_bulletin(latest_tag)
            except Exception as e:
                self.plugin.log_exception("Plugin update check failed", e)
            finally:
                with self._lock:
                    self._inflight = False

        threading.Thread(target=worker, daemon=True).start()

    def _normalize_version_tag(self, value: Optional[str]) -> str:
        if value is None:
            return ""

        normalized = str(value).strip()
        if not normalized:
            return ""

        return normalized.removeprefix("v").removeprefix("V")

    def _fetch_latest_release_tag(self) -> Optional[str]:
        response = requests.get(
            PLUGIN_UPDATE_API_URL,
            headers={
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            timeout=UPDATE_CHECK_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        payload = cast("dict[str, Any]", response.json())
        tag_name = payload.get("tag_name")

        if tag_name is None:
            return None

        normalized = str(tag_name).strip()
        if not normalized:
            return None

        return normalized

    def _show_update_bulletin(self, latest_version: str):
        text = self.plugin._t(
            "update.available.message",
            current=__version__,
            latest=latest_version,
        )
        button_text = self.plugin._t("update.available.action")

        def show():
            if self._stop.is_set():
                return

            try:
                BulletinHelper.show_with_button(
                    text,
                    R_tg.raw.ic_download,
                    button_text,
                    lambda: self.plugin._open_telegram_url(PLUGIN_UPDATE_TG_URL),
                    duration=int(BulletinHelper.DURATION_PROLONG),
                )
            except Exception as e:
                self.plugin.log_exception("Failed to show update bulletin", e)
                self.plugin._show_info(text)

        run_on_ui_thread(show)


class TgStreaksPlugin(BasePlugin):
    """Основной класс плагина"""

    _reinitialize_lock = threading.Lock()
    _full_load_lock = threading.Lock()
    _eject_lock = threading.Lock()

    settings_actions: SettingsActions

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

    def create_settings(self) -> list[Any]:
        if not hasattr(self, "settings_actions"):
            self.settings_actions = SettingsActions(self)

        return [
            Header(text=self._t("settings.updates.title")),
            Switch(
                key=SETTING_UPDATE_CHECK_ENABLED,
                text=self._t("settings.updates.auto_check.title"),
                default=self._is_update_check_enabled(),
                subtext=self._t("settings.updates.auto_check.description"),
                icon="msg_retry",
                on_change=lambda value: self._on_update_check_setting_changed(value),
                link_alias="update-check",
            ),
            *self.settings_actions.build_settings(),
            Header(text=self._t("settings.help.title")),
            Text(
                text=self._t("settings.help.docs.title"),
                icon="msg_info",
                on_click=lambda _: self._open_browser_url(PLUGIN_DOCS_URL),
                link_alias="docs",
            ),
            Divider(text=self._t("settings.help.docs.description")),
        ]

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

    def _should_block_load_for_downgrade(self) -> bool:
        if DEBUG_MODE:
            return False

        previous_version = self._get_last_loaded_version()

        if len(previous_version) == 0 or not _is_version_older(
            __version__, previous_version
        ):
            return False

        self.log(
            f"Plugin was downgraded from {previous_version} to {__version__}: "
            "load aborted"
        )
        self._show_downgrade_dialog(previous_version)

        return True

    def _show_downgrade_dialog(self, previous_version: str):
        def show():
            try:
                fragment = get_last_fragment()
            except Exception:
                fragment = None

            if fragment is None:
                self.log("Downgrade dialog deferred: UI context is unavailable")
                self._schedule_downgrade_dialog_retry(previous_version)
                return

            self_outer = self

            class ChannelClickListener(
                dynamic_proxy(AlertDialog.OnButtonClickListener)
            ):
                def onClick(self, _dialog: AlertDialog, _which: int) -> None:  # ty: ignore[invalid-method-override]
                    self_outer._open_telegram_url(PLUGIN_UPDATE_TG_URL)

            message = self._t(
                "dialog.downgrade.message",
                previous=previous_version,
                current=__version__,
            )

            try:
                dialog = (
                    AlertDialog.Builder(fragment.getContext())
                    .setTitle(String(self._t("dialog.downgrade.title")))
                    .setMessage(String(message))
                    .setPositiveButton(
                        String(self._t("dialog.downgrade.channel")),
                        ChannelClickListener(),
                    )
                    .create()
                )
                dialog.setCancelable(False)
                dialog.setCanceledOnTouchOutside(False)

                fragment.showDialog(dialog)
            except Exception as e:
                self.log_exception("Failed to show downgrade dialog", e)
                self._show_error(message)
                self._schedule_downgrade_dialog_retry(previous_version)

        run_on_ui_thread(show)

    def _schedule_downgrade_dialog_retry(self, previous_version: str):
        timer = threading.Timer(
            1.0,
            lambda: self._show_downgrade_dialog(previous_version),
        )
        timer.daemon = True
        timer.start()

    def _should_pause_full_load_for_update(self) -> bool:
        previous_version = self._get_last_loaded_version()

        if len(previous_version) == 0 or previous_version == __version__:
            self._persist_current_loaded_version()
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

    def _is_update_check_enabled(self) -> bool:
        try:
            return bool(self.get_setting(SETTING_UPDATE_CHECK_ENABLED, True))
        except Exception:
            return True

    def _is_auto_streak_creation_enabled(self) -> bool:
        try:
            return bool(self.get_setting(SETTING_AUTO_STREAK_CREATION_ENABLED, True))
        except Exception:
            return True

    def _apply_auto_streak_creation(self, enabled: bool):
        if self.jvm_plugin.klass is None:
            self.log("Auto streak creation update skipped: JVM plugin is not loaded")
            return

        try:
            self.jvm_plugin.klass.getDeclaredMethod(
                String("setAutoStreakCreationEnabled"),
                Boolean.TYPE,
            ).invoke(
                None,
                Boolean(bool(enabled)),
            )
        except Exception as e:
            self.log_exception("Failed to apply auto streak creation setting", e)

    def _on_auto_streak_creation_changed(self, value: bool):
        enabled = bool(value)

        try:
            self.set_setting(SETTING_AUTO_STREAK_CREATION_ENABLED, enabled)
        except Exception as e:
            self.log_exception("Failed to persist auto streak creation setting", e)

        self._apply_auto_streak_creation(enabled)

    def _get_pet_fab_size_index(self) -> int:
        default_index = 1

        try:
            raw_value = int(self.get_setting(SETTING_PET_FAB_SIZE_INDEX, default_index))
        except Exception:
            return default_index

        return max(0, min(raw_value, len(PET_FAB_SIZE_OPTIONS_DP) - 1))

    def _get_pet_fab_size_dp(self) -> int:
        return PET_FAB_SIZE_OPTIONS_DP[self._get_pet_fab_size_index()]

    def _apply_pet_fab_size_dp(self, size_dp: int):
        if self.jvm_plugin.klass is None:
            self.log("Pet FAB size update skipped: JVM plugin is not loaded")
            return

        try:
            self.jvm_plugin.klass.getDeclaredMethod(
                String("setPetFabSizeDp"),
                Integer.TYPE,
            ).invoke(
                None,
                Integer(int(size_dp)),
            )
        except Exception as e:
            self.log_exception("Failed to apply pet FAB size", e)

    def _on_pet_fab_size_changed(self, value: int):
        size_index = max(0, min(int(value), len(PET_FAB_SIZE_OPTIONS_DP) - 1))

        try:
            self.set_setting(SETTING_PET_FAB_SIZE_INDEX, size_index)
        except Exception as e:
            self.log_exception("Failed to persist pet FAB size setting", e)

        self._apply_pet_fab_size_dp(PET_FAB_SIZE_OPTIONS_DP[size_index])

    def _on_update_check_setting_changed(self, enabled: bool):
        enabled = bool(enabled)

        if hasattr(self, "update_checker"):
            self.update_checker.set_enabled(enabled)
            return

        try:
            self.set_setting(SETTING_UPDATE_CHECK_ENABLED, enabled)
        except Exception as e:
            self.log_exception("Failed to persist update check setting", e)

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

    def _open_browser_url(self, url: str):
        def open_url():
            try:
                context = self._resolve_popup_context()
                if context is None:
                    raise RuntimeError("no context")

                intent = Intent()
                intent.setAction(String(Intent.ACTION_VIEW))
                intent.setData(Uri.parse(String(url)))
                intent.addFlags(int(Intent.FLAG_ACTIVITY_NEW_TASK))
                context.startActivity(intent)
            except Exception as e:
                self.log_exception(f"Failed to open Telegram url {url}", e)

        run_on_ui_thread(open_url)

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

    def _finalize_jvm_plugin_inject(self) -> bool:
        try:
            self.jvm_plugin.klass.getDeclaredMethod(  # ty:ignore[possibly-missing-attribute]
                String("finalizeInject")
            ).invoke(None)
            self.log("JVM plugin finalizeInject completed")
        except Exception as e:
            self._handle_load_failure("finalizeInject", e)
            self.on_plugin_eject()
            return False

        return True

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

            self.jvm_plugin.klass.getDeclaredMethod(  # ty:ignore[possibly-missing-attribute]
                String("inject"),
                String.getClass(),
                ValueCallback.getClass(),
                String.getClass(),
            ).invoke(
                None,
                String(__version__),
                Logger(),
                String(self.resources_root),
            )

            self.log("JVM plugin injected successfully")
            self._apply_pet_fab_size_dp(self._get_pet_fab_size_dp())
            self._apply_auto_streak_creation(self._is_auto_streak_creation_enabled())
        except Exception as e:
            self._handle_load_failure("inject", e)
            self.on_plugin_eject()
            return False

        return True

    def _database_file_paths(self) -> list[str]:
        base_path = ApplicationLoader.applicationContext.getDatabasePath(
            String("tg-streaks")
        ).getAbsolutePath()
        return [
            str(base_path),
            f"{base_path}-wal",
            f"{base_path}-shm",
            f"{base_path}-journal",
        ]

    def _backups_dir(self) -> str:
        downloads_dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).getAbsolutePath()
        backups_dir = os.path.join(str(downloads_dir), "tg-streaks")
        os.makedirs(backups_dir, exist_ok=True)
        return backups_dir

    def _list_backup_files(self) -> list[str]:
        backups_dir = self._backups_dir()
        files = []

        try:
            for name in os.listdir(backups_dir):
                path = os.path.join(backups_dir, name)
                if os.path.isfile(path) and name.endswith(".sqlite3"):
                    files.append(path)
        except Exception as e:
            self.log_exception("Failed to list backup files", e)
            return []

        files.sort(key=os.path.getmtime, reverse=True)
        return files

    def _show_restore_backup_file_dialog(self):
        backup_files = self._list_backup_files()

        def show():
            try:
                fragment = get_last_fragment()
            except Exception:
                fragment = None

            if fragment is None:
                self._show_error(
                    self._t("status.error.backup.apply_failed", reason="No UI context")
                )
                return

            names = [os.path.basename(path) for path in backup_files]
            names.append(self._t("dialog.backup_restore.browse"))

            try:
                self_outer = self

                class BackupClickListener(
                    dynamic_proxy(DialogInterface.OnClickListener)
                ):
                    def onClick(self, _dialog, which):  # ty:ignore[invalid-method-override]
                        idx = int(which)
                        if idx < len(backup_files):
                            self_outer._schedule_restore_backup_reinitialize(
                                backup_files[idx]
                            )
                        else:
                            self_outer._open_backup_file_picker(fragment)

                fragment.showDialog(
                    AlertDialog.Builder(fragment.getContext())
                    .setTitle(cast("String", self._t("dialog.backup_restore.title")))
                    .setItems(
                        jarray(String)([String(name) for name in names]),
                        BackupClickListener(),
                    )
                    .create()
                )
            except Exception as e:
                self.log_exception("Failed to show restore backup dialog", e)
                self._show_error(
                    self._t("status.error.backup.apply_failed", reason=str(e))
                )

        run_on_ui_thread(show)

    _BACKUP_FILE_PICKER_REQUEST_CODE = 0x5A7B
    _file_picker_hook_unhooks: "list[Any] | None" = None

    def _cleanup_file_picker_hooks(self):
        unhooks, self._file_picker_hook_unhooks = self._file_picker_hook_unhooks, None
        for u in unhooks or []:
            try:
                u.unhook()
            except Exception:
                pass

    def _open_backup_file_picker(self, fragment):
        if self._file_picker_hook_unhooks is not None:
            return

        self_outer = self

        class ActivityResultHook(MethodHook):
            def after_hooked_method(self, param):
                request_code = int(param.args[0])
                if request_code != self_outer._BACKUP_FILE_PICKER_REQUEST_CODE:
                    return

                self_outer._cleanup_file_picker_hooks()

                result_code = int(param.args[1])
                if result_code != -1:  # Activity.RESULT_OK
                    return

                data_intent = param.args[2]
                if data_intent is None:
                    return

                uri = data_intent.getData()
                if uri is None:
                    return

                self_outer._restore_from_uri(uri)

        try:
            from hook_utils import find_class

            base_fragment_class = find_class("org.telegram.ui.ActionBar.BaseFragment")
            if base_fragment_class is None:
                raise RuntimeError("BaseFragment class not found")

            self._file_picker_hook_unhooks = self.hook_all_methods(
                base_fragment_class,
                "onActivityResultFragment",
                ActivityResultHook(),
            )

            intent = Intent()
            intent.setAction(String(Intent.ACTION_GET_CONTENT))
            intent.setType(String("*/*"))
            intent.addCategory(String(Intent.CATEGORY_OPENABLE))

            fragment.startActivityForResult(
                intent, self._BACKUP_FILE_PICKER_REQUEST_CODE
            )
        except Exception as e:
            self._cleanup_file_picker_hooks()
            self.log_exception("Failed to open backup file picker", e)
            self._show_error(self._t("status.error.backup.apply_failed", reason=str(e)))

    def _restore_from_uri(self, uri):
        def worker():
            try:
                context = ApplicationLoader.applicationContext
                cr = context.getContentResolver()

                display_name = "backup"
                try:
                    cursor = cr.query(uri, None, None, None, None)  # ty:ignore[invalid-argument-type]
                    if cursor is not None and cursor.moveToFirst():
                        col = cursor.getColumnIndex(String("_display_name"))
                        if col >= 0:
                            display_name = str(cursor.getString(col))
                        cursor.close()
                except Exception:
                    pass

                temp_path = os.path.join(
                    str(context.getCacheDir().getAbsolutePath()),
                    "tg-streaks-restore-import.sqlite3",
                )

                input_stream = cr.openInputStream(uri)
                try:
                    buf = jarray(jbyte)(65536)
                    with open(temp_path, "wb") as f:
                        while True:
                            n = input_stream.read(buf)
                            if n < 0:
                                break
                            f.write(bytes(buf[:n]))
                finally:
                    input_stream.close()

                self._schedule_restore_backup_reinitialize(temp_path, display_name)
            except Exception as e:
                self.log_exception("Failed to read backup from URI", e)
                self._show_error(
                    self._t("status.error.backup.apply_failed", reason=str(e))
                )

        threading.Thread(
            target=worker,
            name="tg-streaks-backup-restore-from-uri",
            daemon=True,
        ).start()

    def _schedule_restore_backup_reinitialize(
        self, backup_path: str, display_name: str | None = None
    ):
        name = display_name or os.path.basename(backup_path)
        reason = f"Backup restore requested from settings: {name}"

        if not self._reinitialize_lock.acquire(blocking=False):
            self.log(f"Skipped duplicate backup restore request: {reason}")
            return

        def worker():
            try:
                self.log(f"Starting backup restore: {reason}")

                if not os.path.isfile(backup_path):
                    self._show_error(self._t("status.error.backup.not_found"))
                    return

                try:
                    self.on_plugin_unload()
                except BaseException as e:
                    self.log_exception(
                        "Failed during plugin unload before backup restore", e
                    )

                try:
                    target_path = self._database_file_paths()[0]
                    os.makedirs(os.path.dirname(target_path), exist_ok=True)

                    for path in self._database_file_paths()[1:]:
                        if os.path.exists(path):
                            os.remove(path)

                    if os.path.exists(target_path):
                        os.remove(target_path)

                    shutil.copy(backup_path, target_path)
                    self.log(f"Database restored from backup: {backup_path}")
                except BaseException as e:
                    self.log_exception("Failed to apply backup file", e)
                    self._show_error(
                        self._t("status.error.backup.apply_failed", reason=str(e))
                    )
                    return

                try:
                    load_thread = self.on_plugin_load(allow_update_pause=False)
                except BaseException as e:
                    self.log_exception(
                        "Failed during plugin load after backup restore", e
                    )
                    return

                if load_thread is not None:
                    load_thread.join()

                self._show_success(
                    self._t(
                        "status.success.backup.imported",
                        name=name,
                    )
                )
                self.log("Backup restore and plugin reinitialization completed")
            finally:
                self._reinitialize_lock.release()

        threading.Thread(
            target=worker,
            name="tg-streaks-backup-restore-reinitialize",
            daemon=True,
        ).start()

    def _schedule_database_reset_reinitialize(self):
        reason = "Database reset requested from settings"

        if not self._reinitialize_lock.acquire(blocking=False):
            self.log(f"Skipped duplicate database reset request: {reason}")
            return

        def worker():
            try:
                self.log(f"Starting database reset: {reason}")

                try:
                    self.on_plugin_unload()
                except BaseException as e:
                    self.log_exception("Failed during plugin unload before DB reset", e)

                try:
                    for path in self._database_file_paths():
                        if os.path.exists(path):
                            os.remove(path)
                            self.log(f"Deleted database file: {path}")
                except BaseException as e:
                    self.log_exception("Failed to delete plugin database", e)
                    self._show_error(
                        self._t("status.error.database.delete_failed", reason=str(e))
                    )
                    return

                self._show_success(self._t("status.success.database.reset_started"))

                try:
                    load_thread = self.on_plugin_load(allow_update_pause=False)
                except BaseException as e:
                    self.log_exception("Failed during plugin load after DB reset", e)
                    return

                if load_thread is not None:
                    load_thread.join()

                self.log("Database reset and plugin reinitialization completed")
            finally:
                self._reinitialize_lock.release()

        threading.Thread(
            target=worker,
            name="tg-streaks-db-reset-reinitialize",
            daemon=True,
        ).start()

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

            self.settings_actions = SettingsActions(self)
            self.chat_context_menu = ChatContextMenu(self)
            self.chat_context_menu.register()

            if not self._finalize_jvm_plugin_inject():
                return

            self.update_checker = PluginUpdateChecker(self)
            self.update_checker.start()

            self.badges_sdk.load()
        except BaseException as e:
            self._handle_load_failure("plugin load", e)
            return

        self._stop_load_logging()

    def on_plugin_load(
        self, allow_update_pause: bool = True
    ) -> threading.Thread | None:
        self._reset_load_log_buffer()
        self._ejected = False

        try:
            self.assets = EmbeddedAssets(self)
            self.resources_bridge = ZipResourcesBridge(self)
            self._full_load_started = False

            if self._should_block_load_for_downgrade():
                return None

            self.badges_sdk = BadgesSdk(self)

            if not DEBUG_MODE:
                self.badges_sdk.remove_standalone_plugin()

            if allow_update_pause and self._should_pause_full_load_for_update():
                return None

            return self._continue_plugin_load()
        except BaseException as e:
            self._handle_load_failure("plugin load", e)
            return None

    def on_plugin_unload(self):
        try:
            self.update_checker.stop()
        except Exception:
            pass

        badges_sdk = getattr(self, "badges_sdk", None)

        if badges_sdk is not None:
            badges_sdk.unload()

        jvm_plugin = getattr(self, "jvm_plugin", None)

        if jvm_plugin is None or jvm_plugin.klass is None:
            return

        try:
            if getattr(self, "chat_context_menu", None) is not None:
                self.chat_context_menu.unregister()
        except Exception as e:
            self.log_exception("Failed to unregister chat context menu", e)

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

        try:
            if getattr(self, "chat_context_menu", None) is not None:
                self.chat_context_menu.unregister()
        except Exception as e:
            self.log_exception("Failed to unregister chat context menu after eject", e)

        try:
            if getattr(self, "update_checker", None) is not None:
                self.update_checker.stop()
        except Exception:
            pass

        jvm_plugin = getattr(self, "jvm_plugin", None)
        if jvm_plugin is not None:
            jvm_plugin.klass = None


# === EMDEDDED DEX BEGIN ===
# === EMDEDDED DEX END ===
# === EMDEDDED RESOURCES BEGIN ===
# === EMDEDDED RESOURCES END ===
# === EMDEDDED BADGES SDK BEGIN ===
# === EMDEDDED BADGES SDK END ===
