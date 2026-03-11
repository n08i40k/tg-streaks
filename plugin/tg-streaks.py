import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass
from enum import Enum
from functools import wraps
from typing import (
    Callable,
    Concatenate,
    Optional,
    ParamSpec,
    TypeVar,
    cast,
)

import requests
from android.app import Dialog
from android.content import DialogInterface, Intent
from android.graphics import Color, Typeface
from android.graphics.drawable import ColorDrawable, GradientDrawable
from android.net import Uri
from android.view import Gravity, WindowManager
from android.webkit import ValueCallback
from android.widget import ImageView, LinearLayout, TextView
from android_utils import jclass, log, run_on_ui_thread
from base_plugin import (
    BasePlugin,
    HookResult,
    MenuItemData,
    MenuItemType,
    MethodHook,
)
from client_utils import (
    get_account_instance,
    get_connections_manager,
    get_last_fragment,
    get_messages_controller,
)
from dalvik.system import InMemoryDexClassLoader
from hook_utils import get_private_field
from java import (
    dynamic_proxy,
    jarray,
    jlong,
)
from java.lang import (
    Boolean,
    Class,
    Integer,
    Long,
    Object,
    String,
    System,
)
from java.util import Calendar, TimeZone
from java.util.concurrent import ConcurrentHashMap
from java.util.function import Function
from org.telegram.messenger import (
    AndroidUtilities,
    ApplicationLoader,
    DialogObject,
    LocaleController,
    MessageObject,
    MessagesController,
    NotificationCenter,
    SendMessagesHelper,
    UserConfig,
    UserObject,
)
from org.telegram.messenger import R as R_tg  # ty:ignore[unresolved-import]
from org.telegram.tgnet import TLRPC, RequestDelegate, TLObject
from org.telegram.ui import ChatActivity, LaunchActivity
from org.telegram.ui.Components import AnimatedEmojiDrawable
from typing_extensions import Any
from ui.bulletin import BulletinHelper
from ui.settings import Divider, Header, Switch, Text

__id__ = "tg-streaks"
__name__ = "Streaks"
__description__ = "Analog for TikTok streaks for Telegram"
__author__ = "@n08i40k"
__version__ = "1.6.0"
__icon__ = "exteraPlugins/0"
__min_version__ = "12.2.10"

DEBUG_MODE = False

REPO_OWNER = "n08i40k"
REPO_NAME = __id__

SECONDS_IN_DAY = 86400
DAY_CHECK_TIMEOUT_SECONDS = 15
DAY_CHECK_TIMEOUT_RETRIES = 5
DAY_CHECK_REQUEST_DELAY_SECONDS = 0.35
DAY_CHECK_RETRY_DELAY_SECONDS = 0.8
DAY_CHECK_MESSAGE_TABLE_CANDIDATES = ("messages_v2", "messages")
UPGRADE_SERVICE_MESSAGE_PREFIX = "tg-streaks:upgrade:"
DEATH_SERVICE_MESSAGE_TEXT = "tg-streaks:death"
RESTORE_SERVICE_MESSAGE_TEXT = "tg-streaks:restore"

DEX_URL = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download/{__version__}/classes.dex"
DEX_SHA256 = "208b5a6059e4289431b8dd7c41cc473b6e267492f38e9087f288b8aa61f706dc"

PLUGIN_UPDATE_TG_URL = "tg://resolve?domain=n08i40k_extera&post=3"
UPDATE_CHECK_TIMEOUT_SECONDS = 6
SETTING_UPDATE_CHECK_ENABLED = "update_check_enabled"
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

_day_check_messages_table_name: Optional[str] = None
_day_check_messages_table_resolved = False

I18N_STRINGS: dict[str, dict[str, str]] = {
    "settings_updates": {"en": "Updates", "ru": "Обновления"},
    "settings_check_updates": {
        "en": "Check for plugin updates",
        "ru": "Проверять обновления плагина",
    },
    "settings_check_updates_hint": {
        "en": "Checks the latest GitHub release in the background when the plugin loads.",
        "ru": "При загрузке плагина в фоне проверяет последний релиз на GitHub.",
    },
    "settings_streak_tools": {"en": "Streak Tools", "ru": "Инструменты стрика"},
    "settings_force_check_all_private_chats": {
        "en": "Recalculate streaks in all private chats",
        "ru": "Пересчитать стрики во всех личных чатах",
    },
    "settings_only_private_hint": {
        "en": "Only private dialogs with real users are checked. Bots and groups are skipped.",
        "ru": "Проверяются только личные диалоги с реальными пользователями. Боты и группы пропускаются.",
    },
    "settings_db_backups": {"en": "Database Backups", "ru": "Резервные копии базы"},
    "settings_export_backup_now": {
        "en": "Create backup now",
        "ru": "Создать резервную копию сейчас",
    },
    "settings_import_latest_backup": {
        "en": "Restore from latest backup",
        "ru": "Восстановить из последней копии",
    },
    "settings_db_backups_hint": {
        "en": "Daily backups are created automatically. Import replaces current streak database.",
        "ru": "Ежедневные бэкапы создаются автоматически. Импорт заменяет текущую базу стриков.",
    },
    "err_users_db_not_ready": {
        "en": "Users database is not ready yet",
        "ru": "База пользователей ещё не готова",
    },
    "err_cannot_detect_current_chat": {
        "en": "Cannot detect current chat",
        "ru": "Не удалось определить текущий чат",
    },
    "err_force_check_failed_logs": {
        "en": "Force check failed. See plugin logs.",
        "ru": "Принудительная проверка завершилась ошибкой. Смотрите логи плагина.",
    },
    "err_cannot_open_chat_context": {
        "en": "Cannot open chat context for jump",
        "ru": "Не удалось открыть контекст чата для перехода",
    },
    "err_failed_jump_to_streak_start": {
        "en": "Failed to jump to streak start",
        "ru": "Не удалось перейти к началу стрика",
    },
    "err_failed_export_backup": {
        "en": "Failed to export backup",
        "ru": "Не удалось экспортировать бэкап",
    },
    "err_backup_export_failed": {
        "en": "Backup export failed",
        "ru": "Ошибка экспорта бэкапа",
    },
    "err_backup_import_failed": {
        "en": "Backup import failed",
        "ru": "Ошибка импорта бэкапа",
    },
    "err_failed_open_update_link": {
        "en": "Failed to open update link",
        "ru": "Не удалось открыть ссылку обновления",
    },
    "ok_backup_exported": {
        "en": "Backup exported: {name}",
        "ru": "Бэкап экспортирован: {name}",
    },
    "ok_backup_imported": {
        "en": "Backup imported: {name}",
        "ru": "Бэкап импортирован: {name}",
    },
    "ok_jumped_to_streak_start_message": {
        "en": "Jumped to streak start message",
        "ru": "Переход к сообщению начала стрика выполнен",
    },
    "ok_debug_streak_set_3": {
        "en": "Debug: streak set to 3 days",
        "ru": "Debug: стрик установлен на 3 дня",
    },
    "ok_debug_streak_marked_dead": {
        "en": "Debug: streak marked as dead",
        "ru": "Debug: стрик помечен как мёртвый",
    },
    "ok_debug_streak_upgraded": {
        "en": "Debug: streak upgraded to {days} days",
        "ru": "Debug: стрик улучшен до {days} дней",
    },
    "ok_upgrade_service_messages_enabled": {
        "en": "Upgrade service messages enabled for this chat",
        "ru": "Сервисные сообщения об апгрейде включены для этого чата",
    },
    "ok_upgrade_service_messages_disabled": {
        "en": "Upgrade service messages disabled for this chat",
        "ru": "Сервисные сообщения об апгрейде выключены для этого чата",
    },
    "ok_streak_restored": {
        "en": "Streak restored",
        "ru": "Стрик восстановлен",
    },
    "info_private_user_only": {
        "en": "This action works only for private user chats",
        "ru": "Это действие работает только в личных чатах с пользователями",
    },
    "info_private_chat_only": {
        "en": "This action is available only in a private chat with another person",
        "ru": "Это действие доступно только в личном чате с другим человеком",
    },
    "info_action_not_available_for_saved_messages": {
        "en": "This action is not available in Saved Messages",
        "ru": "Это действие недоступно в Избранном",
    },
    "info_action_not_available_for_your_other_account": {
        "en": "This action is not available in chats with your other account",
        "ru": "Это действие недоступно в чате с вашим другим аккаунтом",
    },
    "info_action_not_available_for_bots": {
        "en": "This action is not available in chats with bots",
        "ru": "Это действие недоступно в чатах с ботами",
    },
    "info_action_not_available_for_deleted_users": {
        "en": "This action is not available for deleted accounts",
        "ru": "Это действие недоступно для удалённых аккаунтов",
    },
    "info_streak_restore_unavailable": {
        "en": "This streak can no longer be restored",
        "ru": "Этот стрик больше нельзя восстановить",
    },
    "info_force_check_already_running": {
        "en": "Force check is already running",
        "ru": "Принудительная проверка уже выполняется",
    },
    "info_force_check_started_chat": {
        "en": "Force check started for this chat",
        "ru": "Принудительная проверка запущена для этого чата",
    },
    "info_force_check_started_all": {
        "en": "Force check started",
        "ru": "Принудительная проверка запущена",
    },
    "info_no_streak_record_for_chat": {
        "en": "No streak record for this chat",
        "ru": "Для этого чата нет записи стрика",
    },
    "info_searching_streak_start_message": {
        "en": "Searching streak start message...",
        "ru": "Ищу сообщение начала стрика...",
    },
    "info_exact_start_message_not_found": {
        "en": "Exact start message not found, jumped to streak start day",
        "ru": "Точное сообщение начала не найдено, выполнен переход к дню начала стрика",
    },
    "info_debug_mode_disabled": {
        "en": "Debug mode is disabled",
        "ru": "Debug-режим отключён",
    },
    "info_debug_private_user_only": {
        "en": "Debug actions work only for private user chats",
        "ru": "Debug-действия работают только в личных чатах с пользователями",
    },
    "info_debug_streak_already_max": {
        "en": "Debug: streak is already at max level",
        "ru": "Debug: стрик уже на максимальном уровне",
    },
    "popup_streak_ended_title": {"en": "Streak Ended", "ru": "Стрик завершён"},
    "popup_streak_started_title": {"en": "Streak Started", "ru": "Стрик начат"},
    "popup_streak_upgraded_title": {"en": "{days} DAYS!!!", "ru": "{days} ДНЕЙ!!!"},
    "popup_streak_ended_subtitle": {
        "en": "Your streak with {name} ended after {days} days!",
        "ru": "Ваш стрик с {name} завершился после {days} дней!",
    },
    "popup_streak_started_subtitle": {
        "en": "You started a streak with {name}!",
        "ru": "Вы начали стрик с {name}!",
    },
    "popup_streak_upgraded_subtitle": {
        "en": "Your streak with {name} leveled up!",
        "ru": "Ваш стрик с {name} повысил уровень!",
    },
    "dex_sheet_feature_how_title": {
        "en": "How does this work?",
        "ru": "Как это работает?",
    },
    "dex_sheet_feature_how_subtitle": {
        "en": "After three days of communication in a row, you will have a streak that improves depending on its duration!",
        "ru": "После трёх дней общения подряд у вас появится стрик, который улучшается в зависимости от длительности!",
    },
    "dex_sheet_feature_levels_title": {
        "en": "What levels are there?",
        "ru": "Какие уровни есть?",
    },
    "dex_sheet_feature_levels_subtitle": {
        "en": "There are levels for 3, 10, 30, 100, and 200+ consecutive days of communication. A pop-up will appear when your streak level improves.",
        "ru": "Есть уровни за 3, 10, 30, 100 и 200+ дней общения подряд. При повышении уровня стрика появится всплывающее окно.",
    },
    "dex_sheet_feature_keep_title": {
        "en": "Don't forget about it ;)",
        "ru": "Не забывайте про него ;)",
    },
    "dex_sheet_feature_keep_subtitle": {
        "en": "If you don't manage to message each other within 24 hours, the streak will end. It can be restored only within the next 24 hours.",
        "ru": "Если вы не успеете написать друг другу в течение 24 часов, стрик завершится. Его можно восстановить только в течение следующих 24 часов.",
    },
    "dex_sheet_feature_incorrect_title": {
        "en": "Streak duration is incorrect?",
        "ru": "Длительность стрика неверная?",
    },
    "dex_sheet_feature_incorrect_subtitle": {
        "en": 'This can be fixed! Tap "Recalculate streak in this chat" to recount the streak length.',
        "ru": 'Это можно исправить! Нажмите "Пересчитать стрик в этом чате", чтобы заново посчитать его длину.',
    },
    "dex_sheet_title": {
        "en": "You and {name} have been on a streak for {days} days now!",
        "ru": "У Вас и {name} стрик уже более {days} дней!",
    },
    "dex_sheet_subtitle": {
        "en": "Keep chatting and keep the streak going **:P**",
        "ru": "Продолжайте общаться и не прерывайте стрик **:P**",
    },
    "menu_force_check_chat_text": {
        "en": "Recalculate streak in this chat",
        "ru": "Пересчитать стрик в этом чате",
    },
    "menu_force_check_chat_subtext": {
        "en": "Check the chat history again and update the streak length",
        "ru": "Ещё раз проверить историю чата и обновить длину стрика",
    },
    "menu_go_to_streak_start_text": {
        "en": "Open where the streak began",
        "ru": "Открыть начало стрика",
    },
    "menu_go_to_streak_start_subtext": {
        "en": "Jump to the message or day where the current streak started",
        "ru": "Перейти к сообщению или дню, с которого начался текущий стрик",
    },
    "menu_upgrade_service_messages_text": {
        "en": "Messages about streak level-ups",
        "ru": "Сообщения о повышении уровня стрика",
    },
    "menu_upgrade_service_messages_subtext": {
        "en": "Show or hide service messages in this chat when the streak reaches a new level",
        "ru": "Показывать или скрывать в этом чате служебные сообщения о новом уровне стрика",
    },
    "menu_restore_streak_text": {
        "en": "Restore streak",
        "ru": "Восстановить стрик",
    },
    "menu_restore_streak_subtext": {
        "en": "Available only during the first 24 hours after the streak ends",
        "ru": "Доступно только в течение первых 24 часов после прерывания стрика",
    },
    "menu_debug_create_streak_text": {
        "en": "[DEBUG] Create 3-day streak",
        "ru": "[DEBUG] Создать стрик на 3 дня",
    },
    "menu_debug_create_streak_subtext": {
        "en": "Set current chat streak to 3 days",
        "ru": "Установить стрик текущего чата на 3 дня",
    },
    "menu_debug_kill_streak_text": {
        "en": "[DEBUG] Kill streak",
        "ru": "[DEBUG] Убить стрик",
    },
    "menu_debug_kill_streak_subtext": {
        "en": "Force streak death for current chat",
        "ru": "Принудительно завершить стрик в текущем чате",
    },
    "menu_debug_upgrade_streak_text": {
        "en": "[DEBUG] Upgrade streak",
        "ru": "[DEBUG] Улучшить стрик",
    },
    "menu_debug_upgrade_streak_subtext": {
        "en": "Upgrade current chat to next streak level",
        "ru": "Повысить стрик текущего чата до следующего уровня",
    },
    "force_check_day_mode_today": {"en": "today pass", "ru": "проверка сегодня"},
    "force_check_day_mode_yesterday": {"en": "yesterday pass", "ru": "проверка вчера"},
    "force_check_day_state_active": {"en": "active", "ru": "активно"},
    "force_check_day_state_stop": {"en": "stop", "ru": "стоп"},
    "force_check_day_progress_chat": {
        "en": "Force check day progress: {days_checked} days (offset={day_offset}, {state}, {mode})",
        "ru": "Прогресс проверки по дням: {days_checked} дней (смещение={day_offset}, {state}, {mode})",
    },
    "force_check_day_progress_all": {
        "en": "Force check day progress: {days_checked} days for {peer_name} [{checked_chats}/{total_chats}] (offset={day_offset}, {state}, {mode})",
        "ru": "Прогресс проверки по дням: {days_checked} дней для {peer_name} [{checked_chats}/{total_chats}] (смещение={day_offset}, {state}, {mode})",
    },
    "force_check_progress_zero": {
        "en": "Force check progress: 0/0",
        "ru": "Прогресс проверки: 0/0",
    },
    "force_check_progress": {
        "en": "Force check progress: {checked}/{total} (updated={updated}, removed={removed}, unchanged={unchanged})",
        "ru": "Прогресс проверки: {checked}/{total} (обновлено={updated}, удалено={removed}, без изменений={unchanged})",
    },
    "force_check_summary_chat": {
        "en": "Force check done for this chat: checked={checked}, updated={updated}, removed={removed}, unchanged={unchanged}",
        "ru": "Проверка этого чата завершена: проверено={checked}, обновлено={updated}, удалено={removed}, без изменений={unchanged}",
    },
    "force_check_summary_all": {
        "en": "Force check done: checked={checked}, updated={updated}, removed={removed}, unchanged={unchanged}",
        "ru": "Проверка завершена: проверено={checked}, обновлено={updated}, удалено={removed}, без изменений={unchanged}",
    },
    "db_err_no_backups_found": {"en": "No backups found", "ru": "Бэкапы не найдены"},
    "db_err_failed_list_backups": {
        "en": "Failed to list backups: {reason}",
        "ru": "Не удалось получить список бэкапов: {reason}",
    },
    "db_err_failed_read_backup": {
        "en": "Failed to read backup: {reason}",
        "ru": "Не удалось прочитать бэкап: {reason}",
    },
    "db_err_failed_apply_backup": {
        "en": "Failed to apply backup: {reason}",
        "ru": "Не удалось применить бэкап: {reason}",
    },
    "update_bulletin_text": {
        "en": "Streaks plugin update available!",
        "ru": "Доступна обновление плагина Streaks!",
    },
    "update_bulletin_button": {
        "en": "Update",
        "ru": "Обновить",
    },
    "service_message_upgrade_text": {
        "en": "Streak upgraded to {days} days!",
        "ru": "Стрик достиг {days} дней!",
    },
    "service_message_death_title": {
        "en": "Your streak has ended!",
        "ru": "Ваш стрик завершился!",
    },
    "service_message_death_subtitle": {
        "en": "You can restore it within the next 24 hours",
        "ru": "Вы можете восстановить его в течение следующих 24 часов",
    },
    "service_message_death_hint": {
        "en": "Tap the button below to restore your streak",
        "ru": "Нажмите кнопку ниже, чтобы восстановить стрик",
    },
    "service_message_death_button": {
        "en": "Restore",
        "ru": "Восстановить",
    },
    "service_message_restore_text_self": {
        "en": "You restored the streak!",
        "ru": "Вы восстановили стрик!",
    },
    "service_message_restore_text_peer": {
        "en": "{name} restored the streak!",
        "ru": "{name} восстановил(а) стрик!",
    },
}


class TimeUtils:
    @staticmethod
    def get_server_timestamp() -> int:
        local_timestamp = int(time.time())

        try:
            timestamp = int(get_connections_manager().getCurrentTime())
            if timestamp > 0 and abs(timestamp - local_timestamp) <= (
                2 * SECONDS_IN_DAY
            ):
                return timestamp
        except Exception:
            pass

        return local_timestamp

    @staticmethod
    def get_server_timezone() -> TimeZone:
        return TimeZone.getTimeZone(String("UTC"))

    @staticmethod
    def get_user_timezone() -> TimeZone:
        return TimeZone.getDefault()

    @staticmethod
    def strip_timestamp_in_timezone(timestamp: int, timezone: TimeZone) -> int:
        calendar = Calendar.getInstance(timezone)
        calendar.setTimeInMillis(int(timestamp) * 1000)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.HOUR_OF_DAY, 0)

        return int(calendar.getTimeInMillis() / 1000)

    @staticmethod
    def get_stripped_timestamp_in_timezone(
        day_offset: int, timezone: TimeZone, now_timestamp: Optional[int] = None
    ) -> int:
        if now_timestamp is None:
            now_timestamp = TimeUtils.get_server_timestamp()

        calendar = Calendar.getInstance(timezone)
        calendar.setTimeInMillis(int(now_timestamp) * 1000)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.HOUR_OF_DAY, 0)

        if day_offset != 0:
            calendar.add(Calendar.DAY_OF_MONTH, int(day_offset))

        return int(calendar.getTimeInMillis() / 1000)

    @staticmethod
    def strip_timestamp(timestamp: int) -> int:
        # User-local day bucket. Used for streak freeze logic.
        return TimeUtils.strip_timestamp_in_timezone(
            timestamp, TimeUtils.get_user_timezone()
        )

    @staticmethod
    def strip_server_timestamp(timestamp: int) -> int:
        # Server day bucket. Used for request ranges and comparisons.
        return TimeUtils.strip_timestamp_in_timezone(
            timestamp, TimeUtils.get_server_timezone()
        )

    @staticmethod
    def get_stripped_timestamp(day_offset: int = 0) -> int:
        # User-local day bucket. Freeze must happen at local midnight.
        return TimeUtils.get_stripped_timestamp_in_timezone(
            day_offset, TimeUtils.get_user_timezone(), now_timestamp=int(time.time())
        )

    @staticmethod
    def get_server_stripped_timestamp(day_offset: int = 0) -> int:
        # Server day bucket for history checks.
        return TimeUtils.get_stripped_timestamp_in_timezone(
            day_offset, TimeUtils.get_server_timezone()
        )

    @staticmethod
    def get_stripped_boundaries(day_offset: int) -> tuple[int, int]:
        return (
            TimeUtils.get_stripped_timestamp(day_offset),
            TimeUtils.get_stripped_timestamp(day_offset + 1),
        )

    @staticmethod
    def get_server_stripped_boundaries(day_offset: int) -> tuple[int, int]:
        return (
            TimeUtils.get_server_stripped_timestamp(day_offset),
            TimeUtils.get_server_stripped_timestamp(day_offset + 1),
        )

    @staticmethod
    def get_server_day_offset(timestamp: int) -> int:
        server_today = TimeUtils.get_server_stripped_timestamp()
        timestamp_day = TimeUtils.strip_server_timestamp(timestamp)
        return int((timestamp_day - server_today) // SECONDS_IN_DAY)

    @staticmethod
    def get_local_day_offset(timestamp: int) -> int:
        local_today = TimeUtils.get_stripped_timestamp()
        timestamp_day = TimeUtils.strip_timestamp(timestamp)
        return int((timestamp_day - local_today) // SECONDS_IN_DAY)


class JvmPluginBridge:
    klass: Optional[Class]

    def __init__(self, plugin: BasePlugin):
        self.plugin = plugin
        self.klass = None

        self.cache_dir = os.path.join(
            ApplicationLoader.applicationContext.getExternalFilesDir(
                None  # ty:ignore[invalid-argument-type]
            ).getAbsolutePath(),
            "plugins_dex_cache",
        )

        if not os.path.exists(self.cache_dir):
            os.makedirs(self.cache_dir)

        self.dex_path = os.path.join(self.cache_dir, f"{__id__}.dex")

    def load(self):
        if DEBUG_MODE:
            self._download()
            self._load_cached_file()
            return

        expected_sha256 = str(DEX_SHA256).strip().lower()

        cached_sha256 = self._compute_file_sha256(self.dex_path)
        if cached_sha256 is not None and cached_sha256 == expected_sha256:
            self.plugin.log("Using cached DEX (SHA256 matched)")
            self._load_cached_file()
            return

        if cached_sha256 is None:
            self.plugin.log("Cached DEX not found. Downloading new file...")
        else:
            self.plugin.log(
                f"Cached DEX SHA256 mismatch (cached={cached_sha256}, expected={expected_sha256}). Downloading new file..."
            )

        dex_data = self._download_bytes()
        if dex_data is None:
            return

        downloaded_sha256 = self._compute_sha256(dex_data)
        if downloaded_sha256 != expected_sha256:
            self._force_disable_plugin(
                "Downloaded DEX SHA256 mismatch. Plugin was disabled for safety."
            )
            return

        self._write_dex_file(dex_data)
        self._load(dex_data)

    def _load_cached_file(self):
        try:
            with open(self.dex_path, "rb") as f:
                self._load(f.read())
        except Exception as e:
            self.plugin.log(str(e))

    def _compute_sha256(self, data: bytes) -> str:
        return hashlib.sha256(data).hexdigest().lower()

    def _compute_file_sha256(self, path: str) -> Optional[str]:
        if not os.path.exists(path):
            return None

        try:
            with open(path, "rb") as f:
                return self._compute_sha256(f.read())
        except Exception as e:
            self.plugin.log(f"Failed to read cached DEX for SHA256: {e}")
            return None

    def _write_dex_file(self, dex_data: bytes):
        with open(self.dex_path, "wb") as f:
            f.write(dex_data)

    def _download_bytes(self) -> Optional[bytes]:
        try:
            self.plugin.log("Downloading DEX...")
            r = requests.get(DEX_URL, timeout=10)
            if r.status_code == 200:
                return cast("bytes", r.content)
            self.plugin.log(f"Failed to download DEX: {r.status_code}")
            return None
        except Exception as e:
            self.plugin.log(f"Failed to download DEX: {e}")
            return None

    def _force_disable_plugin(self, reason: str):
        self.plugin.log(reason)

        try:
            setattr(self.plugin, "enabled", False)
        except Exception:
            pass

        raise RuntimeError(reason)

    def _download(self):
        dex_data = self._download_bytes()
        if dex_data is None:
            return
        self._write_dex_file(dex_data)

    def _load(self, dex_data):
        class_path = "ru.n08i40k.streaks.Plugin"

        try:
            loader = InMemoryDexClassLoader(
                jclass("java.nio.ByteBuffer").wrap(dex_data),  # ty:ignore[unresolved-attribute]
                ApplicationLoader.applicationContext.getClassLoader(),
            )

            self.klass = loader.loadClass(String(class_path))

        except Exception as e:
            self.plugin.log(f"Failed to load DEX: {e}")


def _resolve_day_check_messages_table_name(db: Any) -> Optional[str]:
    global _day_check_messages_table_name
    global _day_check_messages_table_resolved

    if _day_check_messages_table_resolved:
        return _day_check_messages_table_name

    for table_name in DAY_CHECK_MESSAGE_TABLE_CANDIDATES:
        cursor = None
        try:
            cursor = db.queryFinalized(f"SELECT out FROM {table_name} LIMIT 1")
            _day_check_messages_table_name = str(table_name)
            _day_check_messages_table_resolved = True
            return _day_check_messages_table_name
        except Exception:
            pass
        finally:
            if cursor is not None:
                try:
                    cursor.dispose()
                except Exception:
                    pass

    _day_check_messages_table_name = None
    _day_check_messages_table_resolved = True
    return None


def _try_get_cached_day_activity(
    peer: TLRPC.InputPeer, start_ts: int, end_ts: int
) -> Optional[bool]:
    try:
        dialog_id = int(getattr(peer, "user_id", 0))
    except Exception:
        return None

    if dialog_id <= 0:
        return None

    try:
        storage = get_account_instance().getMessagesStorage()
        db = storage.getDatabase()
    except Exception:
        return None

    table_name = _resolve_day_check_messages_table_name(db)
    if table_name is None:
        return None

    cursor = None

    try:
        cursor = db.queryFinalized(
            (
                String(
                    f"SELECT "
                    f"MAX(CASE WHEN out = 1 THEN 1 ELSE 0 END), "
                    f"MAX(CASE WHEN out = 0 THEN 1 ELSE 0 END) "
                    f"FROM {table_name} "
                    f"WHERE uid = ? AND date >= ? AND date < ?"
                )
            ),
            Integer(int(dialog_id)),
            Integer(int(start_ts)),
            Integer(int(end_ts)),
        )

        if cursor is None or not cursor.next():
            return None

        has_out = 0 if cursor.isNull(0) else int(cursor.intValue(0))
        has_in = 0 if cursor.isNull(1) else int(cursor.intValue(1))

        if has_out == 1 and has_in == 1:
            return True

        return None
    except Exception:
        return None
    finally:
        if cursor is not None:
            try:
                cursor.dispose()
            except Exception:
                pass


def was_chat_active(
    peer: TLRPC.InputPeer, day_offset: int, fail_on_error: bool = False
) -> bool:
    start_ts, end_ts = TimeUtils.get_stripped_boundaries(day_offset)
    _, server_end_ts = TimeUtils.get_server_stripped_boundaries(day_offset)
    initial_offset_date = int(max(end_ts, server_end_ts))

    cached_result = _try_get_cached_day_activity(peer, start_ts, end_ts)
    if cached_result is not None:
        return cached_result

    attempts = DAY_CHECK_TIMEOUT_RETRIES + 1
    last_state: Optional[dict[str, Any]] = None

    def run_single_attempt() -> dict[str, Any]:
        done = threading.Event()
        state = {
            "offset_id": 0,
            "offset_date": initial_offset_date,
            "from_self": False,
            "from_peer": False,
            "failed": False,
            "timeout": False,
            "failure_reason": "",
        }

        def load():
            req = TLRPC.TL_messages_getHistory()
            req.peer = peer
            req.offset_id = int(state["offset_id"])
            req.offset_date = int(state["offset_date"])
            req.limit = 10

            class Delegate(dynamic_proxy(RequestDelegate)):
                def run(self, response: TLObject, error: TLRPC.TL_error | None) -> None:
                    if error is not None:
                        state["failed"] = True
                        try:
                            state["failure_reason"] = f"request error: {error.text}"
                        except Exception:
                            state["failure_reason"] = f"request error: {error}"
                        done.set()
                        log(error)
                        return

                    response = cast("TLRPC.messages_Messages", response)
                    msgs = response.messages

                    if msgs is None or msgs.size() == 0:
                        done.set()
                        return

                    for i in range(msgs.size()):
                        m = msgs.get(i)
                        d = int(m.date)

                        if start_ts <= d < end_ts:
                            if m.from_id.user_id == peer.user_id:
                                state["from_peer"] = True
                            else:
                                state["from_self"] = True

                            if state["from_self"] and state["from_peer"]:
                                done.set()
                                return

                    oldest = msgs.get(msgs.size() - 1)
                    state["offset_id"] = int(oldest.id)
                    state["offset_date"] = int(oldest.date)

                    if int(oldest.date) >= start_ts:
                        load()
                    else:
                        done.set()

            conn_m = get_connections_manager()
            conn_m.sendRequest(req, Delegate())

        load()

        if not done.wait(timeout=DAY_CHECK_TIMEOUT_SECONDS):
            state["failed"] = True
            state["timeout"] = True
            state["failure_reason"] = (
                f"day check timed out after {DAY_CHECK_TIMEOUT_SECONDS}s "
                f"(attempt {attempt + 1}/{attempts})"
            )

        return state

    for attempt in range(attempts):
        state = run_single_attempt()
        last_state = state

        if state.get("timeout", False) and attempt < (attempts - 1):
            if DAY_CHECK_RETRY_DELAY_SECONDS > 0:
                time.sleep(float(DAY_CHECK_RETRY_DELAY_SECONDS))
            continue

        if fail_on_error and state["failed"]:
            reason = cast("str", state.get("failure_reason", "")) or "unknown"
            raise RuntimeError(f"day activity check failed: {reason}")

        return state["from_peer"] and state["from_self"]

    if fail_on_error:
        reason = "unknown"
        if last_state is not None:
            reason = cast("str", last_state.get("failure_reason", "")) or "unknown"
        raise RuntimeError(f"day activity check failed: {reason}")

    return False


def find_restore_service_days(
    peer: TLRPC.InputPeer,
    start_ts: int,
    end_ts: int,
    fail_on_error: bool = False,
) -> set[int]:
    done = threading.Event()
    restored_days: set[int] = set()
    initial_offset_date = int(max(end_ts, TimeUtils.get_server_stripped_timestamp()))
    state = {
        "offset_id": 0,
        "offset_date": initial_offset_date,
        "failed": False,
        "failure_reason": "",
    }

    def load():
        req = TLRPC.TL_messages_getHistory()
        req.peer = peer
        req.offset_id = int(state["offset_id"])
        req.offset_date = int(state["offset_date"])
        req.limit = 100

        class Delegate(dynamic_proxy(RequestDelegate)):
            def run(self, response: TLObject, error: TLRPC.TL_error | None) -> None:
                if error is not None:
                    state["failed"] = True
                    try:
                        state["failure_reason"] = f"request error: {error.text}"
                    except Exception:
                        state["failure_reason"] = f"request error: {error}"
                    done.set()
                    return

                response_obj = cast("TLRPC.messages_Messages", response)
                msgs = response_obj.messages

                if msgs is None or msgs.size() == 0:
                    done.set()
                    return

                for i in range(msgs.size()):
                    message = msgs.get(i)
                    message_date = int(getattr(message, "date", 0))

                    if not (start_ts <= message_date < end_ts):
                        continue

                    try:
                        message_text = str(getattr(message, "message", "")).strip()
                    except Exception:
                        message_text = ""

                    if message_text == RESTORE_SERVICE_MESSAGE_TEXT:
                        restore_day = TimeUtils.strip_timestamp(message_date)
                        restored_days.add(restore_day)
                        if restore_day > SECONDS_IN_DAY:
                            restored_days.add(restore_day - SECONDS_IN_DAY)

                oldest = msgs.get(msgs.size() - 1)
                oldest_date = int(oldest.date)
                state["offset_id"] = int(oldest.id)
                state["offset_date"] = oldest_date

                if oldest_date >= start_ts:
                    load()
                else:
                    done.set()

        get_connections_manager().sendRequest(req, Delegate())

    load()

    if not done.wait(timeout=60):
        state["failed"] = True
        state["failure_reason"] = "restore history scan timed out"

    if fail_on_error and state["failed"]:
        raise RuntimeError(cast("str", state["failure_reason"]) or "unknown")

    return restored_days


def get_streak_length(peer_id: int):
    peer = get_messages_controller().getInputPeer(peer_id)  # ty:ignore[no-matching-overload]

    streak_days = 0

    while True:
        if not was_chat_active(peer, -streak_days):
            break

        streak_days += 1

    return streak_days


def get_streak_die_date(peer_id: int, day_offset: int) -> Optional[int]:
    if day_offset > 0:
        raise Exception("Day offset must be negative or zero")

    peer = get_messages_controller().getInputPeer(peer_id)  # ty:ignore[no-matching-overload]

    while True:
        if not was_chat_active(peer, day_offset):
            break

        day_offset += 1

        if day_offset > 0:
            return None

    return day_offset


def get_streak_die_date_from_freeze_ts(peer_id: int, freeze_ts: int) -> Optional[int]:
    return get_streak_die_date(peer_id, TimeUtils.get_local_day_offset(freeze_ts))


class StreakLevel:
    document_id: int
    accent_color: Color
    accent_color_int: int

    def __init__(self, document_id: int, accent_color: tuple[int, int, int]):
        self.document_id = document_id
        self.accent_color = Color.valueOf(
            accent_color[0] / 255, accent_color[1] / 255, accent_color[2] / 255, 1.0
        )
        self.accent_color_int = Color.rgb(
            int(accent_color[0]), int(accent_color[1]), int(accent_color[2])
        )


class StreakLevels(Enum):
    COLD = StreakLevel(5285071881815235305, (175, 175, 175))
    DAYS_3 = StreakLevel(5285079178964672780, (255, 154, 0))
    DAYS_10 = StreakLevel(5285274844789777412, (255, 100, 0))
    DAYS_30 = StreakLevel(5285076623459129616, (255, 61, 0))
    DAYS_100 = StreakLevel(5285003347022093599, (255, 0, 200))
    DAYS_200 = StreakLevel(5285514817497504375, (176, 0, 255))

    def pick_by_length(length: int, cold: bool = False) -> StreakLevel:
        if cold:
            return StreakLevels.COLD.value

        if length < 10:
            return StreakLevels.DAYS_3.value
        if length < 30:
            return StreakLevels.DAYS_10.value
        if length < 100:
            return StreakLevels.DAYS_30.value
        if length < 200:
            return StreakLevels.DAYS_100.value

        return StreakLevels.DAYS_200.value

    def is_jubilee(length: int) -> bool:
        return length in (3, 10, 30) or (length > 0 and length % 100 == 0)

    def get_next_level_length(length: int) -> Optional[int]:
        if length < 3:
            return 3

        if length < 10:
            return 10

        if length < 30:
            return 30

        if length < 100:
            return 100

        if length < 200:
            return 200

        return None


class SenderType(Enum):
    SELF = 0
    PEER = 1


P = ParamSpec("P")
R = TypeVar("R")
S = TypeVar("S", bound=object)


def cache_by_field_value(
    *,
    field_name: str,
) -> Callable[[Callable[Concatenate[S, P], R]], Callable[Concatenate[S, P], R]]:
    def inner(method: Callable[Concatenate[S, P], R]) -> Callable[Concatenate[S, P], R]:
        cache_attr = f"__cache__{method.__name__}__by_{field_name}"  # ty:ignore[unresolved-attribute]
        lock_attr = f"__lock__{method.__name__}__by_{field_name}"  # ty:ignore[unresolved-attribute]

        @wraps(method)
        def wrapper(self: S, *args: P.args, **kwargs: P.kwargs) -> R:
            field_value = getattr(self, field_name, None)

            val_cache = getattr(self, cache_attr, None)

            if val_cache is None:
                val_cache = {}
                setattr(self, cache_attr, val_cache)

            val_lock: Optional[threading.RLock] = getattr(self, lock_attr, None)

            if val_lock is None:
                val_lock = threading.RLock()
                setattr(self, lock_attr, val_lock)

            with val_lock:
                if field_value in val_cache:
                    return val_cache[field_value]

                value = method(self, *args, **kwargs)
                val_cache.clear()
                val_cache[field_value] = value
                return value

        return wrapper

    return inner


@dataclass
class UserRecord:
    user_id: int

    started_at: int
    freezes_at: int

    last_sended_at: Optional[int]
    last_received_at: Optional[int]
    restored_days: list[int]

    @staticmethod
    def _normalize_restored_days(days: Any) -> list[int]:
        if days is None:
            return []

        normalized: list[int] = []
        seen: set[int] = set()

        for raw_day in days:
            try:
                day = TimeUtils.strip_timestamp(int(raw_day))
            except Exception:
                continue

            if day <= 0 or day in seen:
                continue

            seen.add(day)
            normalized.append(day)

        normalized.sort()
        return normalized

    def to_dict(self) -> dict[str, Any]:
        return {
            "user_id": int(self.user_id),
            "started_at": int(self.started_at),
            "freezes_at": int(self.freezes_at),
            "last_sended_at": None
            if self.last_sended_at is None
            else int(self.last_sended_at),
            "last_received_at": None
            if self.last_received_at is None
            else int(self.last_received_at),
            "restored_days": list(self.restored_days),
        }

    @staticmethod
    def from_dict(payload: dict[str, Any]) -> Optional["UserRecord"]:
        try:
            user_id = int(payload["user_id"])
            started_at = int(payload["started_at"])
            freezes_at = int(payload["freezes_at"])
        except Exception:
            return None

        if user_id <= 0:
            return None

        last_sended_at_raw = payload.get("last_sended_at")
        last_received_at_raw = payload.get("last_received_at")
        restored_days = UserRecord._normalize_restored_days(
            payload.get("restored_days", [])
        )

        return UserRecord(
            user_id=user_id,
            started_at=started_at,
            freezes_at=freezes_at,
            last_sended_at=(
                None if last_sended_at_raw is None else int(last_sended_at_raw)
            ),
            last_received_at=(
                None if last_received_at_raw is None else int(last_received_at_raw)
            ),
            restored_days=restored_days,
        )

    @staticmethod
    def new(peer_id: int, sender: SenderType, event_day: Optional[int] = None):
        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )

        self = UserRecord(
            user_id=peer_id,
            started_at=day,
            freezes_at=(day + SECONDS_IN_DAY),
            last_sended_at=(day if sender == SenderType.SELF else None),
            last_received_at=(day if sender == SenderType.PEER else None),
            restored_days=[],
        )

        return self

    def get_length(self) -> int:
        current_day = TimeUtils.get_stripped_timestamp()
        length = (current_day - self.started_at) // SECONDS_IN_DAY

        if not self.is_freezed():
            length += 1

        last_counted_day = (
            current_day if not self.is_freezed() else current_day - SECONDS_IN_DAY
        )
        restored_days_count = sum(
            1
            for day in self.restored_days
            if self.started_at <= day <= last_counted_day
        )

        return max(length - restored_days_count, 0)

    def is_freezed(self) -> bool:
        return TimeUtils.get_stripped_timestamp() >= self.freezes_at

    def should_die(self) -> bool:
        return (
            (TimeUtils.get_stripped_timestamp() - self.freezes_at) // SECONDS_IN_DAY
        ) > 0

    def can_restore(self, event_day: Optional[int] = None) -> bool:
        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )
        return self.should_die() and day < (self.freezes_at + 2 * SECONDS_IN_DAY)

    def is_restored_day(self, event_day: Optional[int] = None) -> bool:
        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )
        return day in self.restored_days

    def add_restored_day(self, event_day: int) -> bool:
        day = TimeUtils.strip_timestamp(event_day)

        if day <= 0 or day in self.restored_days:
            return False

        self.restored_days.append(day)
        self.restored_days.sort()
        return True

    def clear_restored_days(self) -> bool:
        if not self.restored_days:
            return False

        self.restored_days = []
        return True

    def revive(self, event_day: Optional[int] = None) -> bool:
        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )

        if not self.can_restore(day):
            return False

        changed = False
        changed = self.add_restored_day(self.freezes_at) or changed
        changed = self.add_restored_day(day) or changed

        next_freeze = day + SECONDS_IN_DAY
        if next_freeze != self.freezes_at:
            self.freezes_at = next_freeze
            changed = True

        return changed

    def apply_remote_restore(self, event_day: int) -> bool:
        day = TimeUtils.strip_timestamp(event_day)

        if day <= 0:
            return False

        if day < self.freezes_at:
            return False

        if day >= (self.freezes_at + 2 * SECONDS_IN_DAY):
            return False

        changed = False
        changed = self.add_restored_day(self.freezes_at) or changed
        changed = self.add_restored_day(day) or changed

        next_freeze = day + SECONDS_IN_DAY
        if next_freeze > self.freezes_at:
            self.freezes_at = next_freeze
            changed = True

        return changed

    @cache_by_field_value(field_name="freezes_at")
    def is_alive(self) -> bool:  # "hungry" function, shouldn't be called frequently
        if not self.is_freezed():
            return True

        if not self.should_die():
            return True

        # History checks are server-day based; freeze timestamp itself is user-day based.
        death_date = get_streak_die_date_from_freeze_ts(self.user_id, self.freezes_at)

        return (
            death_date is None or death_date == 0
        )  # can be freezed, is death date is today

    def update_freeze_date(self):
        self.freezes_at = TimeUtils.get_stripped_timestamp(
            1
        )  # will be freezed from tomorrow

    def update_from(
        self, sender_type: SenderType, event_day: Optional[int] = None
    ) -> bool:
        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )

        changed = False

        if self.is_restored_day(day):
            return False

        if sender_type == SenderType.SELF:
            if self.last_sended_at is None or day > self.last_sended_at:
                self.last_sended_at = day
                changed = True
        else:
            if self.last_received_at is None or day > self.last_received_at:
                self.last_received_at = day
                changed = True

        if self.last_sended_at is None or self.last_received_at is None:
            return changed

        if self.last_sended_at == self.last_received_at:
            # Move freeze date to the next day of the latest day with both-side activity.
            next_freeze = max(self.freezes_at, self.last_sended_at + SECONDS_IN_DAY)
            if next_freeze != self.freezes_at:
                self.freezes_at = next_freeze
                changed = True

        return changed


def reset_drawable_cache(jvm_plugin: JvmPluginBridge):
    klass = jvm_plugin.klass

    if klass is None:
        return

    klass.getDeclaredMethod(String("clearCaches")).invoke(None)  # ty:ignore[invalid-argument-type]


def get_authorized_user_ids() -> list[int]:
    result = []
    for i in range(UserConfig.MAX_ACCOUNT_COUNT):
        cfg = UserConfig.getInstance(i)
        if cfg.isClientActivated():
            result.append(cfg.getClientUserId())
    return result


class UsersDatabase:
    def __init__(
        self,
        jvm_plugin: JvmPluginBridge,
        logger: Callable[[str], None],
        storage_path: Optional[str] = None,
    ):
        self._map: dict[int, UserRecord] = dict()
        self._lock = threading.RLock()
        self._jvm_plugin = jvm_plugin
        self._logger = logger
        self._storage_path = storage_path or self._build_storage_path()
        self._storage_dir = os.path.dirname(self._storage_path)
        self._backups_dir = os.path.join(self._storage_dir, "backups")
        os.makedirs(self._backups_dir, exist_ok=True)
        self._load()
        self.ensure_daily_backup()

    def _build_storage_path(self) -> str:
        files_dir = ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath()
        storage_dir = os.path.join(files_dir, "chaquopy", "plugins", __id__)
        if not os.path.exists(storage_dir):
            os.makedirs(storage_dir, exist_ok=True)
        return os.path.join(storage_dir, "users_db.json")

    def _build_payload_locked(self) -> dict[str, Any]:
        return {
            "version": 1,
            "saved_at": int(time.time()),
            "users": [record.to_dict() for record in self._map.values()],
        }

    def _write_payload_to_path(self, payload: dict[str, Any], target_path: str):
        tmp_path = f"{target_path}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=True, separators=(",", ":"))
        os.replace(tmp_path, target_path)

    def _load_map_from_payload(self, payload: dict[str, Any]) -> dict[int, UserRecord]:
        users_payload = payload.get("users", [])
        loaded_map: dict[int, UserRecord] = {}

        for row in users_payload:
            if not isinstance(row, dict):
                continue

            record = UserRecord.from_dict(cast("dict[str, Any]", row))
            if record is None:
                continue

            loaded_map[record.user_id] = record

        return loaded_map

    def _daily_backup_path(self, day_key: str) -> str:
        return os.path.join(self._backups_dir, f"users_db.daily.{day_key}.json")

    def _get_local_day_key(self) -> str:
        return time.strftime("%Y-%m-%d", time.localtime())

    def _ensure_daily_backup_locked(self) -> Optional[str]:
        day_key = self._get_local_day_key()
        target_path = self._daily_backup_path(day_key)

        if os.path.exists(target_path):
            return target_path

        payload = self._build_payload_locked()
        self._write_payload_to_path(payload, target_path)
        return target_path

    def _collect_backup_paths_locked(self) -> list[str]:
        if not os.path.exists(self._backups_dir):
            return []

        backup_paths = []
        for name in os.listdir(self._backups_dir):
            if not name.endswith(".json"):
                continue
            backup_paths.append(os.path.join(self._backups_dir, name))

        backup_paths.sort(key=lambda path: os.path.getmtime(path), reverse=True)
        return backup_paths

    def ensure_daily_backup(self):
        try:
            with self._lock:
                self._ensure_daily_backup_locked()
        except Exception as e:
            self._logger(f"UsersDatabase daily backup failed: {e}")

    def export_backup_now(self) -> Optional[str]:
        with self._lock:
            payload = self._build_payload_locked()
            stamp = time.strftime("%Y-%m-%d_%H-%M-%S", time.localtime())
            target_path = os.path.join(
                self._backups_dir, f"users_db.export.{stamp}.json"
            )
            self._write_payload_to_path(payload, target_path)
            return target_path

    def import_latest_backup(self) -> tuple[bool, str]:
        try:
            with self._lock:
                backup_paths = self._collect_backup_paths_locked()
        except Exception as e:
            return (False, f"Failed to list backups: {e}")

        if not backup_paths:
            return (False, "No backups found")

        latest_path = backup_paths[0]
        return self.import_backup(latest_path)

    def import_backup(self, backup_path: str) -> tuple[bool, str]:
        try:
            with open(backup_path, "r", encoding="utf-8") as f:
                payload = cast("dict[str, Any]", json.load(f))
            loaded_map = self._load_map_from_payload(payload)
        except Exception as e:
            return (False, f"Failed to read backup: {e}")

        changed = False
        try:
            with self._lock:
                if self._map != loaded_map:
                    self._map = loaded_map
                    self._persist_locked()
                    changed = True
        except Exception as e:
            return (False, f"Failed to apply backup: {e}")

        if changed:
            reset_drawable_cache(self._jvm_plugin)

        self._logger(
            f"UsersDatabase backup imported: {backup_path}, records={len(loaded_map)}"
        )
        return (True, backup_path)

    def get_backups_dir(self) -> str:
        return self._backups_dir

    def _persist_locked(self):
        payload = self._build_payload_locked()
        self._write_payload_to_path(payload, self._storage_path)
        self._ensure_daily_backup_locked()

    def _load(self):
        if not os.path.exists(self._storage_path):
            return

        try:
            with open(self._storage_path, "r", encoding="utf-8") as f:
                payload = json.load(f)
            loaded_map = self._load_map_from_payload(cast("dict[str, Any]", payload))

            with self._lock:
                self._map = loaded_map

            self._logger(f"UsersDatabase loaded: {len(loaded_map)} records")
        except Exception as e:
            self._logger(f"UsersDatabase load failed: {e}")

    def save(self):
        try:
            with self._lock:
                self._persist_locked()
            reset_drawable_cache(self._jvm_plugin)
        except Exception as e:
            self._logger(f"UsersDatabase save failed: {e}")

    def get_user(
        self, user_id: int, include_authorized: bool = False
    ) -> Optional[UserRecord]:
        if user_id <= 0:
            return None

        if not include_authorized and user_id in get_authorized_user_ids():
            return None

        with self._lock:
            return self._map.get(user_id)

    def list_user_ids(self) -> list[int]:
        with self._lock:
            return list(self._map.keys())

    def list_users(self) -> list[UserRecord]:
        with self._lock:
            return list(self._map.values())

    def set_user_record(
        self,
        record: UserRecord,
        persist: bool = True,
        reset_cache: bool = True,
    ) -> bool:
        changed = False
        with self._lock:
            user_id = int(record.user_id)
            current = self._map.get(user_id)
            if current is None or current.to_dict() != record.to_dict():
                self._map[user_id] = record
                if persist:
                    self._persist_locked()
                changed = True

        if changed and reset_cache:
            reset_drawable_cache(self._jvm_plugin)

        return changed

    def remove_user_record(
        self,
        user_id: int,
        persist: bool = True,
        reset_cache: bool = True,
    ) -> bool:
        changed = False
        with self._lock:
            if self._map.pop(int(user_id), None) is not None:
                if persist:
                    self._persist_locked()
                changed = True

        if changed and reset_cache:
            reset_drawable_cache(self._jvm_plugin)

        return changed


class StreaksController:
    def __init__(
        self,
        users_db: UsersDatabase,
        logger: Callable[[str], None],
    ):
        self._users_db = users_db
        self._logger = logger

    def _copy_record(self, record: UserRecord) -> Optional[UserRecord]:
        return UserRecord.from_dict(record.to_dict())

    def on_dialog_event(
        self, peer_id: int, sender_type: SenderType, event_day: Optional[int] = None
    ):
        if peer_id <= 0:
            return

        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )
        current = self._users_db.get_user(peer_id, include_authorized=True)
        changed = False

        if current is not None and current.should_die():
            if current.can_restore(day):
                return
            current = None

        if current is None:
            record = UserRecord.new(peer_id, sender_type, day)
            changed = True
        else:
            record = UserRecord.from_dict(current.to_dict())
            if record is None:
                record = UserRecord.new(peer_id, sender_type, day)
                changed = True

        changed = record.update_from(sender_type, day) or changed
        if changed:
            self._users_db.set_user_record(record)

    def _get_rebuild_restored_days(self, user_id: int) -> set[int]:
        record = self._users_db.get_user(user_id, include_authorized=True)
        if record is None:
            return set()

        restored_days = set(record.restored_days)

        try:
            peer = get_messages_controller().getInputPeer(user_id)  # ty:ignore[no-matching-overload]
        except Exception:
            peer = None

        if peer is not None:
            scan_start = max(record.freezes_at, record.started_at)
            scan_end = TimeUtils.get_stripped_timestamp(1)

            try:
                restored_days.update(
                    find_restore_service_days(peer, scan_start, scan_end)
                )
            except Exception as e:
                self._logger(f"restore history scan failed for {user_id}: {e}")

        if record.should_die() and not restored_days and not record.can_restore():
            return set()

        return restored_days

    def _build_recent_record(
        self,
        user_id: int,
        include_today: bool,
        restored_days: Optional[set[int]] = None,
        on_day_progress: Optional[Callable[[int, bool, bool], None]] = None,
        fail_on_error: bool = False,
    ) -> Optional[UserRecord]:
        peer = get_messages_controller().getInputPeer(user_id)  # ty:ignore[no-matching-overload]
        if peer is None:
            return None

        restored_days = restored_days or set()

        first_day_check = True

        def check_day(day_offset: int) -> bool:
            nonlocal first_day_check

            if not first_day_check and DAY_CHECK_REQUEST_DELAY_SECONDS > 0:
                time.sleep(float(DAY_CHECK_REQUEST_DELAY_SECONDS))

            first_day_check = False
            return was_chat_active(peer, day_offset, fail_on_error=fail_on_error)

        day_offset = 0 if include_today else -1
        oldest_day: Optional[int] = None
        latest_active_day: Optional[int] = None

        while True:
            is_active = check_day(day_offset)
            if on_day_progress is not None:
                on_day_progress(day_offset, is_active, include_today)

            day = TimeUtils.get_stripped_timestamp(day_offset)
            if not is_active and day not in restored_days:
                break

            oldest_day = day
            if is_active and day not in restored_days and latest_active_day is None:
                latest_active_day = day

            day_offset -= 1

        if oldest_day is None or latest_active_day is None:
            return None

        latest_day = (
            TimeUtils.get_stripped_timestamp()
            if include_today
            else TimeUtils.get_stripped_timestamp(-1)
        )
        active_restored_days = [
            day for day in sorted(restored_days) if oldest_day <= day <= latest_day
        ]

        return UserRecord(
            user_id=user_id,
            started_at=oldest_day,
            freezes_at=latest_day + SECONDS_IN_DAY,
            last_sended_at=latest_active_day,
            last_received_at=latest_active_day,
            restored_days=active_restored_days,
        )

    def _build_recent_record_any(
        self,
        user_id: int,
        on_day_progress: Optional[Callable[[int, int, bool, bool], None]] = None,
        fail_on_error: bool = False,
    ) -> Optional[UserRecord]:
        checked_days = 0
        restored_days = self._get_rebuild_restored_days(user_id)

        def day_progress(day_offset: int, is_active: bool, include_today: bool):
            nonlocal checked_days
            checked_days += 1

            if on_day_progress is not None:
                on_day_progress(checked_days, day_offset, is_active, include_today)

        record = self._build_recent_record(
            user_id,
            include_today=True,
            restored_days=restored_days,
            on_day_progress=day_progress,
            fail_on_error=fail_on_error,
        )

        if record is not None:
            return record

        return self._build_recent_record(
            user_id,
            include_today=False,
            restored_days=restored_days,
            on_day_progress=day_progress,
            fail_on_error=fail_on_error,
        )

    def reconcile_on_plugin_load(self) -> tuple[int, int, int]:
        user_ids = self._users_db.list_user_ids()
        checked = 0
        updated = 0
        removed = 0
        changed = False

        for user_id in user_ids:
            record = self._users_db.get_user(user_id, include_authorized=True)
            if record is None:
                continue

            if not record.should_die():
                continue

            checked += 1

            try:
                death_date = get_streak_die_date_from_freeze_ts(
                    user_id, record.freezes_at
                )
            except Exception as e:
                self._logger(f"reconcile failed for {user_id}: {e}")
                continue

            if death_date in (None, 0):
                updated_record = self._copy_record(record)
                if updated_record is None:
                    continue

                if death_date is None:
                    updated_record.update_freeze_date()
                else:
                    updated_record.freezes_at = TimeUtils.get_stripped_timestamp()

                if self._users_db.set_user_record(
                    updated_record, persist=False, reset_cache=False
                ):
                    updated += 1
                    changed = True
                continue

            rebuilt = self._build_recent_record(
                user_id,
                include_today=True,
                restored_days=self._get_rebuild_restored_days(user_id),
            )
            if rebuilt is None:
                rebuilt = self._build_recent_record(
                    user_id,
                    include_today=False,
                    restored_days=self._get_rebuild_restored_days(user_id),
                )

            if rebuilt is not None:
                if self._users_db.set_user_record(
                    rebuilt, persist=False, reset_cache=False
                ):
                    updated += 1
                    changed = True
            else:
                if self._users_db.remove_user_record(
                    user_id, persist=False, reset_cache=False
                ):
                    removed += 1
                    changed = True

        if changed:
            self._users_db.save()

        return checked, updated, removed

    def force_check_user_ids(
        self,
        user_ids: list[int],
        on_progress: Optional[Callable[[int, int, int, int, int], None]] = None,
        on_day_progress: Optional[Callable[[int, int, int, bool, bool], None]] = None,
    ) -> tuple[int, int, int, int]:
        authorized_ids = set(get_authorized_user_ids())
        valid_user_ids = [
            user_id
            for user_id in user_ids
            if user_id > 0 and user_id not in authorized_ids
        ]
        total = len(valid_user_ids)
        checked = 0
        updated = 0
        removed = 0
        unchanged = 0
        changed = False

        if on_progress is not None:
            on_progress(0, total, updated, removed, unchanged)

        for user_id in valid_user_ids:
            checked += 1

            try:
                rebuilt = self._build_recent_record_any(
                    user_id,
                    fail_on_error=True,
                    on_day_progress=(
                        None
                        if on_day_progress is None
                        else (
                            lambda day_checked, day_offset, is_active, include_today: (
                                on_day_progress(
                                    user_id,
                                    day_checked,
                                    day_offset,
                                    is_active,
                                    include_today,
                                )
                            )
                        )
                    ),
                )
            except Exception as e:
                self._logger(f"force check failed for {user_id}: {e}")
                if on_progress is not None:
                    on_progress(checked, total, updated, removed, unchanged)
                continue

            current = self._users_db.get_user(user_id, include_authorized=True)
            if rebuilt is None:
                if self._users_db.remove_user_record(
                    user_id, persist=False, reset_cache=False
                ):
                    removed += 1
                    changed = True
                else:
                    unchanged += 1

                if on_progress is not None:
                    on_progress(checked, total, updated, removed, unchanged)
                continue

            if current is None or current.to_dict() != rebuilt.to_dict():
                self._users_db.set_user_record(
                    rebuilt, persist=False, reset_cache=False
                )
                updated += 1
                changed = True
            else:
                unchanged += 1

            if on_progress is not None:
                on_progress(checked, total, updated, removed, unchanged)

        if changed:
            self._users_db.save()

        return checked, updated, removed, unchanged


class TgStreaksPlugin(BasePlugin):
    _chat_upgrade_service_state_cache: dict[int, bool]

    def create_settings(self) -> list[Any]:
        return [
            Header(text=self._t("settings_updates")),
            Switch(
                key=SETTING_UPDATE_CHECK_ENABLED,
                text=self._t("settings_check_updates"),
                default=self._is_update_check_enabled(),
                subtext=self._t("settings_check_updates_hint"),
                icon="msg_retry",
                on_change=lambda value: self._on_update_check_setting_changed(value),
            ),
            Header(text=self._t("settings_streak_tools")),
            Text(
                text=self._t("settings_force_check_all_private_chats"),
                icon="msg_retry",
                on_click=lambda _: self._on_force_check_all_chats_clicked(),
            ),
            Divider(text=self._t("settings_only_private_hint")),
            Header(text=self._t("settings_db_backups")),
            Text(
                text=self._t("settings_export_backup_now"),
                on_click=lambda _: self._on_export_backup_clicked(),
            ),
            Text(
                text=self._t("settings_import_latest_backup"),
                on_click=lambda _: self._on_import_latest_backup_clicked(),
            ),
            Divider(text=self._t("settings_db_backups_hint")),
        ]

    def _show_info(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_info(message))

    def _show_success(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_success(message))

    def _show_error(self, message: str):
        run_on_ui_thread(lambda: BulletinHelper.show_error(message))

    def _is_update_check_enabled(self) -> bool:
        try:
            return bool(self.get_setting(SETTING_UPDATE_CHECK_ENABLED, True))
        except Exception:
            return True

    def _on_update_check_setting_changed(self, enabled: bool):
        enabled = bool(enabled)

        try:
            self.set_setting(SETTING_UPDATE_CHECK_ENABLED, enabled)
        except Exception as e:
            self.log(f"Failed to persist update check setting: {e}")

        if enabled:
            self._start_update_check()
            return

        try:
            self._update_check_stop.set()
        except Exception:
            pass

    def _chat_upgrade_service_setting_key(self, dialog_id: int) -> str:
        return f"upgrade_service_messages:{dialog_id}"

    def _get_upgrade_service_state_cache(self) -> dict[int, bool]:
        cache = getattr(self, "_chat_upgrade_service_state_cache", None)
        if cache is None:
            cache = {}
            self._chat_upgrade_service_state_cache = cache
        return cache

    def _get_chat_upgrade_service_state(self, dialog_id: int) -> Optional[bool]:
        dialog_id = int(dialog_id)

        if dialog_id <= 0:
            return None

        cache = self._get_upgrade_service_state_cache()
        cached = cache.get(dialog_id)
        if cached is not None:
            return cached

        key = self._chat_upgrade_service_setting_key(dialog_id)

        try:
            value = self.get_setting(key, None)
        except Exception:
            return None

        if value is None:
            return None

        if isinstance(value, bool):
            return value

        if isinstance(value, (int, float)):
            return bool(value)

        if isinstance(value, str):
            lowered = value.strip().lower()
        else:
            try:
                lowered = str(value).strip().lower()
            except Exception:
                return None

        if lowered in ("1", "true", "yes", "on"):
            cache[dialog_id] = True
            return True

        if lowered in ("0", "false", "no", "off"):
            cache[dialog_id] = False
            return False

        return None

    def _is_chat_upgrade_service_enabled(self, dialog_id: int) -> bool:
        return self._get_chat_upgrade_service_state(dialog_id) is True

    def _set_chat_upgrade_service_state(self, dialog_id: int, enabled: bool):
        dialog_id = int(dialog_id)
        enabled = bool(enabled)

        if dialog_id <= 0:
            return

        self._get_upgrade_service_state_cache()[dialog_id] = enabled
        key = self._chat_upgrade_service_setting_key(dialog_id)

        try:
            self.set_setting(key, enabled)
        except Exception as e:
            self.log(
                f"Failed to persist upgrade service setting for chat {dialog_id}: {e}"
            )

    def _remember_dialog_account(self, dialog_id: int, account: int):
        dialog_id = int(dialog_id)
        account = int(account)

        if dialog_id <= 0 or account < 0:
            return

        self._dialog_account_map[dialog_id] = account

        if len(self._dialog_account_map) > 512:
            self._dialog_account_map.clear()
            self._dialog_account_map[dialog_id] = account

    def _resolve_dialog_account(self, dialog_id: int, fallback: int = -1) -> int:
        dialog_id = int(dialog_id)

        if dialog_id > 0:
            remembered = self._dialog_account_map.get(dialog_id)
            if remembered is not None:
                return remembered

        return fallback

    def _parse_upgrade_service_message_days(self, text: Any) -> Optional[int]:
        if text is None:
            return None

        try:
            value = str(text).strip()
        except Exception:
            return None

        if not value.startswith(UPGRADE_SERVICE_MESSAGE_PREFIX):
            return None

        suffix = value[len(UPGRADE_SERVICE_MESSAGE_PREFIX) :]
        if len(suffix) == 0 or not suffix.isdigit():
            return None

        try:
            days = int(suffix)
        except Exception:
            return None

        if days <= 0:
            return None

        return days

    def _is_upgrade_service_message_text(self, text: Any) -> bool:
        return self._parse_upgrade_service_message_days(text) is not None

    def _is_death_service_message_text(self, text: Any) -> bool:
        if text is None:
            return False

        try:
            return str(text).strip() == DEATH_SERVICE_MESSAGE_TEXT
        except Exception:
            return False

    def _is_restore_service_message_text(self, text: Any) -> bool:
        if text is None:
            return False

        try:
            return str(text).strip() == RESTORE_SERVICE_MESSAGE_TEXT
        except Exception:
            return False

    def _is_internal_service_message_text(self, text: Any) -> bool:
        return (
            self._is_upgrade_service_message_text(text)
            or self._is_death_service_message_text(text)
            or self._is_restore_service_message_text(text)
        )

    def _safe_log_value(self, value: Any, limit: int = 220) -> str:
        try:
            text = str(value)
        except Exception as e:
            text = f"<unprintable:{self._get_tl_name(e)}>"

        text = text.replace("\n", "\\n")
        if len(text) > limit:
            text = text[:limit] + "..."
        return text

    def _is_tagged_service_message_text(self, text: Any) -> bool:
        if text is None:
            return False

        try:
            return "tg-streaks:" in str(text)
        except Exception:
            return False

    def _has_upgrade_service_text(self, obj: Any) -> bool:
        if obj is None:
            return False
        for attr_name in ("message", "caption"):
            try:
                value = getattr(obj, attr_name, None)
            except Exception:
                value = None

            if self._is_internal_service_message_text(value):
                return True

        return False

    def _is_upgrade_service_message(self, message: Any) -> bool:
        return self._has_upgrade_service_text(message)

    def _is_upgrade_service_send_params(self, params: Any) -> bool:
        return self._has_upgrade_service_text(params)

    def _extract_update_message_text(self, update: Any) -> Optional[str]:
        try:
            text = self._extract_message_text(update)
        except Exception:
            text = None

        if text:
            return text

        try:
            nested_update = getattr(update, "update", None)
        except Exception:
            nested_update = None

        if nested_update is not None:
            try:
                text = self._extract_update_message_text(nested_update)
            except Exception:
                text = None

            if text:
                return text

        return None

    def _log_service_update_trace(
        self,
        stage: str,
        account: int,
        update_name: str,
        update: Any,
        extra: str = "",
    ):
        try:
            text = self._extract_update_message_text(update)
        except Exception as e:
            self.log(
                f"{stage}: failed to extract update text for {update_name} on account {account}: {e}"
            )
            return

        if not self._is_tagged_service_message_text(text):
            return

        suffix = f", {extra}" if extra else ""
        self.log(
            f"{stage}: account={account}, update={update_name}, text={self._safe_log_value(text)}{suffix}"
        )

    def _maybe_consume_restore_service_message(
        self, account: int, peer_id: int, from_id: Optional[int], event_day: int
    ) -> bool:
        try:
            self_user_id = self._get_self_user_id(account)
            self.log(
                f"Incoming restore service message: account={account}, peer={peer_id}, from_id={from_id}, self_user_id={self_user_id}, event_day={event_day}"
            )

            if from_id is not None and self_user_id > 0 and from_id == self_user_id:
                self.log(
                    f"Skip restore service message for chat {peer_id}: sender is self"
                )
                return True

            current = self.users_db.get_user(peer_id, include_authorized=True)
            if current is None:
                self.log(
                    f"Skip restore service message for chat {peer_id}: no streak record"
                )
                return True

            self.log(
                f"Current restore candidate for chat {peer_id}: started_at={current.started_at}, freezes_at={current.freezes_at}, should_die={current.should_die()}, can_restore_now={current.can_restore()}, can_restore_event_day={current.can_restore(event_day)}, restored_days={current.restored_days}"
            )

            restored = UserRecord.from_dict(current.to_dict())
            if restored is None:
                self.log(
                    f"Skip restore service message for chat {peer_id}: failed to clone record"
                )
                return True

            if restored.apply_remote_restore(event_day):
                self._remember_dialog_account(peer_id, account)
                self.users_db.set_user_record(restored)
                self._streak_dead_state[peer_id] = restored.should_die()

                try:
                    self._refresh_streak_emoji_for_peer(peer_id)
                except Exception as e:
                    self.log(
                        f"Failed to refresh remotely restored streak emoji for peer {peer_id}: {e}"
                    )

                self.log(
                    f"Applied remote streak restore for chat {peer_id} on day {event_day}: new_freezes_at={restored.freezes_at}, new_restored_days={restored.restored_days}"
                )
            else:
                self.log(
                    f"Skip restore service message for chat {peer_id}: remote restore rejected (freezes_at={current.freezes_at}, restored_days={current.restored_days})"
                )
        except Exception as e:
            self.log(f"Restore service handling failed for chat {peer_id}: {e}")

        return True

    def _normalize_version_tag(self, value: Optional[str]) -> str:
        if value is None:
            return ""

        normalized = str(value).strip()
        if len(normalized) == 0:
            return ""

        return normalized.removeprefix("v").removeprefix("V")

    def _open_external_url(self, url: str):
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
                self.log(f"Failed to open external url {url}: {e}")
                self._show_error(self._t("err_failed_open_update_link"))

        run_on_ui_thread(open_url)

    def _show_update_bulletin(self):
        text = self._t("update_bulletin_text")
        button_text = self._t("update_bulletin_button")

        def show():
            if self._update_check_stop.is_set():
                return

            try:
                BulletinHelper.show_with_button(
                    text,
                    R_tg.raw.ic_download,
                    button_text,
                    lambda: self._open_external_url(PLUGIN_UPDATE_TG_URL),
                    duration=int(BulletinHelper.DURATION_PROLONG),
                )
            except Exception as e:
                self.log(f"Failed to show update bulletin: {e}")
                self._show_info(text)

        run_on_ui_thread(show)

    def _fetch_latest_release_tag(self) -> Optional[str]:
        response = requests.get(
            f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest",
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
        if len(normalized) == 0:
            return None

        return normalized

    def _start_update_check(self):
        if DEBUG_MODE:
            self.log("Update check skipped: debug mode is enabled")
            return

        if not self._is_update_check_enabled():
            self.log("Update check skipped: disabled in plugin settings")
            return

        with self._update_check_lock:
            if self._update_check_inflight:
                return
            self._update_check_inflight = True

        self._update_check_stop.clear()

        def worker():
            try:
                latest_tag = self._fetch_latest_release_tag()
                if latest_tag is None:
                    self.log("Update check skipped: latest release tag_name is missing")
                    return

                latest_version = self._normalize_version_tag(latest_tag)
                current_version = self._normalize_version_tag(__version__)

                if len(latest_version) == 0:
                    self.log("Update check skipped: latest release version is empty")
                    return

                if latest_version == current_version:
                    self.log(f"Plugin is up to date: {current_version}")
                    return

                if self._update_check_stop.is_set():
                    return

                self.log(
                    f"Plugin update available: current={__version__}, latest={latest_tag}"
                )
                self._show_update_bulletin()
            except Exception as e:
                self.log(f"Plugin update check failed: {e}")
            finally:
                with self._update_check_lock:
                    self._update_check_inflight = False

        threading.Thread(target=worker, daemon=True).start()

    def _get_app_language_code(self) -> str:
        try:
            locale_controller = LocaleController.getInstance()
        except Exception:
            locale_controller = None

        lang_code = None

        if locale_controller is not None:
            try:
                locale_info = locale_controller.getCurrentLocaleInfo()
            except Exception:
                locale_info = None

            if locale_info is not None:
                try:
                    lang_code = cast("str", locale_info.getLangCode())
                except Exception:
                    lang_code = None

                if lang_code is None or len(str(lang_code)) == 0:
                    try:
                        lang_code = cast("str", locale_info.shortName)
                    except Exception:
                        lang_code = None

            if lang_code is None or len(str(lang_code)) == 0:
                try:
                    current_locale = locale_controller.getCurrentLocale()
                except Exception:
                    current_locale = None

                if current_locale is not None:
                    try:
                        lang_code = cast("str", current_locale.getLanguage())
                    except Exception:
                        lang_code = None

        if lang_code is None:
            return "en"

        normalized = str(lang_code).strip().lower()
        if len(normalized) == 0:
            return "en"

        normalized = normalized.replace("-", "_")
        base_code = normalized.split("_", 1)[0]
        if len(base_code) == 0:
            return "en"

        return base_code

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

    def _localize_db_import_error(self, message: str) -> str:
        if message == "No backups found":
            return self._t("db_err_no_backups_found")

        prefix_map = {
            "Failed to list backups:": "db_err_failed_list_backups",
            "Failed to read backup:": "db_err_failed_read_backup",
            "Failed to apply backup:": "db_err_failed_apply_backup",
        }

        for prefix, key in prefix_map.items():
            if message.startswith(prefix):
                reason = message[len(prefix) :].strip()
                return self._t(key, reason=reason)

        return message

    def _on_export_backup_clicked(self):
        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return

        try:
            backup_path = self.users_db.export_backup_now()
            if backup_path is None:
                self._show_error(self._t("err_failed_export_backup"))
                return

            self.log(f"Backup exported: {backup_path}")
            self._show_success(
                self._t("ok_backup_exported", name=os.path.basename(backup_path))
            )
        except Exception as e:
            self.log(f"Backup export failed: {e}")
            self._show_error(self._t("err_backup_export_failed"))

    def _on_import_latest_backup_clicked(self):
        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return

        try:
            ok, result = self.users_db.import_latest_backup()
            if not ok:
                self._show_error(self._localize_db_import_error(result))
                return

            self._patch_cached_users_streak_emoji_status()
            self._sync_dead_states_snapshot()
            self.log(f"Backup imported: {result}")
            self._show_success(
                self._t("ok_backup_imported", name=os.path.basename(result))
            )
        except Exception as e:
            self.log(f"Backup import failed: {e}")
            self._show_error(self._t("err_backup_import_failed"))

    def _resolve_popup_context(self):
        fragment = None
        context = None

        try:
            fragment = get_last_fragment()
        except Exception:
            fragment = None

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

    def _create_popup_emoji_drawable(
        self,
        parent_view: Any,
        emoji_size_dp: int,
        emoji_document_id: Optional[int],
        color: int,
    ):
        if emoji_document_id is None or int(emoji_document_id) <= 0:
            return None

        try:
            drawable = AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(
                parent_view, int(emoji_size_dp)
            )
            drawable.setParentView(parent_view)
            drawable.setBounds(
                0,
                0,
                AndroidUtilities.dp(float(emoji_size_dp)),
                AndroidUtilities.dp(float(emoji_size_dp)),
            )

            if self.jvm_plugin.klass is not None:
                self.jvm_plugin.klass.getDeclaredMethod(
                    String("enableParticles"),
                    AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable.getClass(),
                    Integer.TYPE,
                ).invoke(None, drawable, color)  # ty:ignore[no-matching-overload]

            try:
                drawable.center = True
            except Exception:
                pass

            cache_type = int(AnimatedEmojiDrawable.getCacheTypeForEnterView())
            if not bool(drawable.set(jlong(int(emoji_document_id)), cache_type, True)):  # ty:ignore[no-matching-overload]
                return None

            drawable.attach()
            drawable.play()
            return drawable
        except Exception as e:
            self.log(f"Failed to build popup streak emoji: {e}")
            return None

    def _show_streak_popup(
        self,
        title: str,
        subtitle: str,
        accent_color: int,
        emoji_document_id: Optional[int] = None,
    ):
        def show():
            context = self._resolve_popup_context()
            if context is None:
                self.log("Skip streak popup: no context")
                return

            try:
                dialog = Dialog(context)
                dialog.requestWindowFeature(1)
                dialog.setCancelable(True)
                dialog.setCanceledOnTouchOutside(True)

                container = LinearLayout(context)
                container.setOrientation(1)
                container.setGravity(Gravity.CENTER_HORIZONTAL)
                container.setPadding(
                    AndroidUtilities.dp(16),
                    AndroidUtilities.dp(14),
                    AndroidUtilities.dp(16),
                    AndroidUtilities.dp(14),
                )

                bg = GradientDrawable()
                bg.setShape(0)
                bg.setColor(Color.argb(200, 20, 20, 24))
                bg.setCornerRadius(float(AndroidUtilities.dp(24)))
                bg.setStroke(AndroidUtilities.dp(1), accent_color)
                container.setBackground(bg)

                emoji_view = None
                emoji_drawable = None
                emoji_size = 0
                if emoji_document_id is not None and int(emoji_document_id) > 0:
                    emoji_view = ImageView(context)
                    emoji_view.setScaleType(ImageView.ScaleType.CENTER_INSIDE)  # ty:ignore[unresolved-attribute]
                    emoji_size_dp = 88 * 2
                    emoji_size = AndroidUtilities.dp(float(emoji_size_dp))
                    emoji_drawable = self._create_popup_emoji_drawable(
                        emoji_view,
                        emoji_size_dp,
                        emoji_document_id,
                        Integer(accent_color),
                    )

                if emoji_drawable is not None and emoji_view is not None:
                    emoji_view.setImageDrawable(emoji_drawable)
                    emoji_lp = LinearLayout.LayoutParams(
                        emoji_size, int(emoji_size / 2)
                    )
                    emoji_lp.bottomMargin = AndroidUtilities.dp(10)
                    container.addView(emoji_view, emoji_lp)

                title_view = TextView(context)
                title_view.setText(String(title))
                title_view.setTextColor(accent_color)
                title_view.setTextSize(24.0)
                title_view.setTypeface(AndroidUtilities.bold())
                title_view.setGravity(Gravity.CENTER_HORIZONTAL)
                title_view.setTypeface(
                    Typeface.create(Typeface.DEFAULT_BOLD, 900, False)
                )
                container.addView(title_view, LinearLayout.LayoutParams(-2, -2))

                subtitle_view = TextView(context)
                subtitle_view.setText(String(subtitle))
                subtitle_view.setTextColor(Color.WHITE)
                subtitle_view.setTextSize(14.0)
                subtitle_view.setGravity(Gravity.CENTER_HORIZONTAL)
                subtitle_lp = LinearLayout.LayoutParams(-2, -2)
                subtitle_lp.topMargin = AndroidUtilities.dp(6)
                container.addView(subtitle_view, subtitle_lp)

                dialog.setContentView(
                    container, LinearLayout.LayoutParams(AndroidUtilities.dp(320), -2)
                )

                window = dialog.getWindow()
                if window is not None:
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setLayout(AndroidUtilities.dp(320), -2)
                    window.setGravity(Gravity.CENTER)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                dialog.show()

                def release_emoji():
                    if emoji_drawable is None:
                        return

                    try:
                        emoji_drawable.detach()
                    except Exception:
                        pass

                class DismissListener(dynamic_proxy(DialogInterface.OnDismissListener)):
                    def onDismiss(self, var1):
                        release_emoji()

                dialog.setOnDismissListener(DismissListener())

                def dismiss_popup():
                    try:
                        release_emoji()
                        if dialog.isShowing():
                            dialog.dismiss()
                    except Exception:
                        pass

                threading.Timer(9.2, lambda: run_on_ui_thread(dismiss_popup)).start()
            except Exception as e:
                self.log(f"Failed to show streak popup: {e}")

        run_on_ui_thread(show)

    def _resolve_open_chat_dialog_id(self) -> Optional[int]:
        try:
            fragment = get_last_fragment()
        except Exception:
            return None

        if fragment is None:
            return None

        try:
            dialog_id = int(fragment.getDialogId())
            return dialog_id or None
        except Exception:
            return None

    def _enqueue_streak_popup_for_open_chat(
        self,
        peer_id: int,
        key: str,
        title: str,
        subtitle: str,
        accent_color: int,
        emoji_document_id: Optional[int] = None,
    ):
        if not self._can_show_streak_popup(key):
            return

        peer_id = int(peer_id)
        current_dialog_id = self._resolve_open_chat_dialog_id()

        if current_dialog_id == peer_id:
            self._show_streak_popup(
                title,
                subtitle,
                accent_color,
                emoji_document_id=emoji_document_id,
            )
            return

        popup_payload = {
            "title": str(title),
            "subtitle": str(subtitle),
            "accent_color": int(accent_color),
            "emoji_document_id": (
                None if emoji_document_id is None else int(emoji_document_id)
            ),
        }

        with self._streak_popup_queue_lock:
            queue = self._pending_streak_popups.get(peer_id)
            if queue is None:
                queue = []
                self._pending_streak_popups[peer_id] = queue

            queue.append(popup_payload)
            if len(queue) > 5:
                del queue[:-5]

    def _flush_pending_streak_popup_for_open_chat(self):
        current_dialog_id = self._resolve_open_chat_dialog_id()
        if current_dialog_id is None:
            return

        popup_payload = None

        with self._streak_popup_queue_lock:
            queue = self._pending_streak_popups.get(int(current_dialog_id))
            if queue is None or len(queue) == 0:
                return

            popup_payload = queue.pop(0)
            if len(queue) == 0:
                self._pending_streak_popups.pop(int(current_dialog_id), None)

        if popup_payload is None:
            return

        self._show_streak_popup(
            cast("str", popup_payload["title"]),
            cast("str", popup_payload["subtitle"]),
            int(popup_payload["accent_color"]),
            emoji_document_id=cast("Optional[int]", popup_payload["emoji_document_id"]),
        )

    def _resolve_peer_name(self, peer_id: int) -> str:
        try:
            user = self._resolve_dialog_user(get_messages_controller(), int(peer_id))
            if user is not None:
                name = cast("str", UserObject.getUserName(user))
                if name is not None and len(name) > 0:
                    return name
        except Exception:
            pass

        return f"id:{peer_id}"

    def _can_show_streak_popup(self, key: str, cooldown_seconds: int = 20) -> bool:
        now = int(time.time())
        last = int(self._streak_popup_last.get(key, 0))

        if now - last < cooldown_seconds:
            return False

        self._streak_popup_last[key] = now
        return True

    def _resolve_level_accent_color(self, level_id: int) -> int:
        target_level_id = int(level_id)

        for level in StreakLevels:
            level = cast(StreakLevel, level.value)

            if int(level.document_id) == target_level_id:
                return int(level.accent_color_int)

        return Color.rgb(255, 173, 51)

    def _record_state(self, record: Optional[UserRecord]) -> Optional[dict[str, int]]:
        if record is None:
            return None

        length = int(record.get_length())
        dead = 1 if record.should_die() else 0
        level_id = int(
            StreakLevels.pick_by_length(length, record.is_freezed()).document_id
        )

        return {
            "length": length,
            "dead": dead,
            "level_id": level_id,
        }

    def _enqueue_streak_ended_popup(
        self, peer_id: int, days: int, name: Optional[str] = None
    ):
        days = int(days)
        if days < 3:
            return

        if name is None:
            name = self._resolve_peer_name(peer_id)

        self._enqueue_streak_popup_for_open_chat(
            int(peer_id),
            f"die:{peer_id}:{days}",
            self._t("popup_streak_ended_title"),
            self._t("popup_streak_ended_subtitle", name=name, days=days),
            StreakLevels.COLD.value.accent_color_int,
            emoji_document_id=int(StreakLevels.COLD.value.document_id),
        )

    def _should_send_upgrade_service_message_for_day(
        self, event_day: Optional[int]
    ) -> bool:
        return event_day is None or event_day == TimeUtils.get_stripped_timestamp()

    def _send_internal_service_message(
        self,
        account: int,
        peer_id: int,
        service_text: str,
        dedupe_key: str,
        log_name: str,
    ):
        with self._upgrade_service_lock:
            if dedupe_key in self._sent_upgrade_service_messages:
                return

        def send():
            try:
                target_account = self._resolve_dialog_account(peer_id, account)
                if target_account < 0:
                    try:
                        target_account = get_account_instance().getCurrentAccount()
                    except Exception:
                        target_account = 0

                self.log(
                    f"Sending {log_name} service message: account={target_account}, peer={peer_id}, text={service_text}"
                )
                params = SendMessagesHelper.SendMessageParams.of(
                    String(service_text), peer_id
                )
                SendMessagesHelper.getInstance(target_account).sendMessage(params)

                with self._upgrade_service_lock:
                    self._sent_upgrade_service_messages.add(dedupe_key)
                    if len(self._sent_upgrade_service_messages) > 128:
                        self._sent_upgrade_service_messages.clear()
                        self._sent_upgrade_service_messages.add(dedupe_key)
            except Exception as e:
                self.log(
                    f"Failed to send {log_name} service message for chat {peer_id}: {e}"
                )

        run_on_ui_thread(send)

    def _maybe_send_upgrade_service_message(
        self, account: int, peer_id: int, days: int, event_day: Optional[int]
    ):
        peer_id = int(peer_id)

        if not self._is_chat_upgrade_service_enabled(peer_id):
            return

        if not self._should_send_upgrade_service_message_for_day(event_day):
            return

        event_key_day = (
            TimeUtils.get_stripped_timestamp() if event_day is None else event_day
        )
        self._send_internal_service_message(
            account,
            peer_id,
            f"{UPGRADE_SERVICE_MESSAGE_PREFIX}{days}",
            f"upgrade:{peer_id}:{days}:{event_key_day}",
            "upgrade",
        )

    def _maybe_send_death_service_message(
        self, account: int, peer_id: int, days: int, event_day: Optional[int] = None
    ):
        peer_id = int(peer_id)
        days = int(days)

        if days < 3 or not self._is_chat_upgrade_service_enabled(peer_id):
            return

        if not self._should_send_upgrade_service_message_for_day(event_day):
            return

        record = self.users_db.get_user(peer_id, include_authorized=True)
        if record is None or not record.can_restore(event_day):
            return

        event_key_day = (
            TimeUtils.get_stripped_timestamp() if event_day is None else event_day
        )
        self._send_internal_service_message(
            account,
            peer_id,
            DEATH_SERVICE_MESSAGE_TEXT,
            f"death:{peer_id}:{days}:{event_key_day}",
            "death",
        )

    def _maybe_send_restore_service_message(
        self, account: int, peer_id: int, event_day: Optional[int] = None
    ):
        peer_id = int(peer_id)

        if not self._is_chat_upgrade_service_enabled(peer_id):
            return

        if not self._should_send_upgrade_service_message_for_day(event_day):
            return

        event_key_day = (
            TimeUtils.get_stripped_timestamp() if event_day is None else event_day
        )
        self._send_internal_service_message(
            account,
            peer_id,
            RESTORE_SERVICE_MESSAGE_TEXT,
            f"restore:{peer_id}:{event_key_day}",
            "restore",
        )

    def _force_check_day_labels(
        self, include_today: bool, is_active: bool
    ) -> tuple[str, str]:
        mode = (
            self._t("force_check_day_mode_today")
            if include_today
            else self._t("force_check_day_mode_yesterday")
        )
        state = (
            self._t("force_check_day_state_active")
            if is_active
            else self._t("force_check_day_state_stop")
        )
        return mode, state

    def _maybe_notify_streak_transition(
        self,
        account: int,
        peer_id: int,
        before: Optional[dict[str, int]],
        after: Optional[dict[str, int]],
        event_day: Optional[int] = None,
    ):
        if after is None:
            return

        name = self._resolve_peer_name(peer_id)
        after_length = after["length"]
        after_dead = after["dead"]
        before_length = before["length"] if before is not None else 0
        before_dead = before is not None and before["dead"]

        if after_dead:
            if before is not None and not before["dead"] and before_length >= 3:
                self._enqueue_streak_ended_popup(peer_id, before_length, name=name)
                self._reload_user_in_messages_cache(peer_id)
                self._maybe_send_death_service_message(
                    account, peer_id, before_length, event_day
                )
                try:
                    self._rerender_dialog_cells()
                except Exception as e:
                    self.log(f"Failed to rerender dialogs after streak death: {e}")
            return

        was_started = after_length >= 3 and (
            before is None or before_dead or before_length < 3
        )

        if was_started:
            key = f"start:{peer_id}:{after_length}"
            self._enqueue_streak_popup_for_open_chat(
                peer_id,
                key,
                self._t("popup_streak_started_title"),
                self._t("popup_streak_started_subtitle", name=name),
                StreakLevels.DAYS_3.value.accent_color_int,
                emoji_document_id=after["level_id"],
            )

            self._maybe_send_upgrade_service_message(
                account, peer_id, after_length, event_day
            )

            try:
                self._rerender_dialog_cells()
            except Exception as e:
                self.log(f"Failed to rerender dialogs after streak start: {e}")
            return

        if before is None:
            return

        before_level_id = before["level_id"]
        after_level_id = after["level_id"]
        upgraded = StreakLevels.is_jubilee(after_length)

        if before_level_id != after_level_id and upgraded:
            key = f"upgrade:{peer_id}:{after_level_id}"
            upgraded_level_color = self._resolve_level_accent_color(after_level_id)
            self._enqueue_streak_popup_for_open_chat(
                peer_id,
                key,
                self._t("popup_streak_upgraded_title", days=after_length),
                self._t("popup_streak_upgraded_subtitle", name=name),
                upgraded_level_color,
                emoji_document_id=after_level_id,
            )

            self._maybe_send_upgrade_service_message(
                account, peer_id, after_length, event_day
            )

    def _notify_for_dialog_event(
        self,
        account: int,
        peer_id: int,
        sender_type: SenderType,
        event_day: Optional[int] = None,
    ):
        self._remember_dialog_account(peer_id, account)
        before = self._record_state(self.users_db.get_user(peer_id))
        self.streaks.on_dialog_event(peer_id, sender_type, event_day)
        self._repair_record_from_cached_today_activity(peer_id, event_day)
        after = self._record_state(self.users_db.get_user(peer_id))
        self._maybe_notify_streak_transition(account, peer_id, before, after, event_day)

        try:
            if self._should_refresh_streak_emoji(before, after):
                self._refresh_streak_emoji_for_peer(int(peer_id))
        except Exception as e:
            self.log(f"Failed to refresh streak emoji for peer {peer_id}: {e}")

    def _should_refresh_streak_emoji(
        self,
        before: Optional[dict[str, int]],
        after: Optional[dict[str, int]],
    ) -> bool:
        if before is None and after is None:
            return False

        before_has = before is not None and not before["dead"] and before["length"] >= 3
        after_has = after is not None and not after["dead"] and after["length"] >= 3

        if before_has != after_has:
            return True

        if not after_has:
            return False

        if before is None or after is None:
            return True

        if before["length"] != after["length"]:
            return True

        if before["level_id"] != after["level_id"]:
            return True

        return False

    def _refresh_streak_emoji_for_peer(self, peer_id: int):
        user = None

        try:
            user = get_messages_controller().getUser(Long(int(peer_id)))
        except Exception:
            user = None

        if user is None:
            try:
                users = cast(
                    "ConcurrentHashMap[Long, TLRPC.User]",
                    get_private_field(get_messages_controller(), "users"),
                )
                user = users.get(Long(int(peer_id)))
            except Exception:
                user = None

        record = self.users_db.get_user(int(peer_id))
        has_streak_emoji = (
            record is not None
            and not bool(record.should_die())
            and int(record.get_length()) >= 3
        )

        if user is not None:
            if has_streak_emoji:
                self._patch_user_streak_emoji_status(user)
            else:
                try:
                    user.emoji_status = None  # ty:ignore[invalid-assignment]
                except Exception:
                    pass
                self._post_user_emoji_status_updated(user)
                self._post_emoji_status_interface_update()
        else:
            self._post_emoji_status_interface_update()

        self._post_emoji_loaded_notification()
        self._rerender_dialog_cells()

    def _reload_user_in_messages_cache(self, peer_id: int):
        try:
            controller = get_messages_controller()
            controller.reloadUser(int(peer_id))
        except Exception as e:
            self.log(f"Failed to reload user {peer_id} in MessagesController: {e}")
            return

        def after_reload():
            try:
                self._post_emoji_loaded_notification()
                self._post_emoji_status_interface_update()
                self._rerender_dialog_cells()
            except Exception as e:
                self.log(f"Post-reload refresh failed for user {peer_id}: {e}")

        threading.Timer(0.8, lambda: run_on_ui_thread(after_reload)).start()

    def _sync_dead_states_snapshot(self):
        self._streak_dead_state = {}
        try:
            for record in self.users_db.list_users():
                self._streak_dead_state[int(record.user_id)] = bool(record.should_die())
        except Exception as e:
            self.log(f"Failed to init dead-state snapshot: {e}")

    def _check_dead_state_transitions(self):
        try:
            self.users_db.ensure_daily_backup()
        except Exception:
            pass

        try:
            records = self.users_db.list_users()
        except Exception:
            return

        seen: set[int] = set()
        changed = False

        for record in records:
            user_id = int(record.user_id)
            seen.add(user_id)
            is_dead_now = bool(record.should_die())
            was_dead = bool(self._streak_dead_state.get(user_id, is_dead_now))

            if not was_dead and is_dead_now:
                days = int(record.get_length())
                self._enqueue_streak_ended_popup(user_id, days)
                self._reload_user_in_messages_cache(user_id)
                self._maybe_send_death_service_message(
                    self._resolve_dialog_account(user_id),
                    user_id,
                    days,
                    TimeUtils.get_stripped_timestamp(),
                )
                try:
                    self._rerender_dialog_cells()
                except Exception as e:
                    self.log(f"Failed to rerender dialogs on dead-state monitor: {e}")

            if is_dead_now and not record.can_restore() and record.restored_days:
                updated = UserRecord.from_dict(record.to_dict())
                if updated is not None and updated.clear_restored_days():
                    self.users_db.set_user_record(
                        updated, persist=False, reset_cache=False
                    )
                    changed = True

            self._streak_dead_state[user_id] = is_dead_now

        missing = [
            user_id for user_id in self._streak_dead_state.keys() if user_id not in seen
        ]
        for user_id in missing:
            self._streak_dead_state.pop(user_id, None)

        if changed:
            self.users_db.save()

    def _start_dead_state_monitor(self):
        self._streak_dead_monitor_stop.clear()

        def worker():
            while not self._streak_dead_monitor_stop.wait(30):
                try:
                    self._check_dead_state_transitions()
                except Exception as e:
                    self.log(f"Dead-state monitor error: {e}")

        threading.Thread(target=worker, daemon=True).start()

    def _start_streak_popup_dispatch_monitor(self):
        self._streak_popup_dispatch_stop.clear()

        def worker():
            while not self._streak_popup_dispatch_stop.wait(1.0):
                try:
                    self._flush_pending_streak_popup_for_open_chat()
                except Exception as e:
                    self.log(f"Popup dispatch monitor error: {e}")

        threading.Thread(target=worker, daemon=True).start()

    def _register_chat_action_menu_items(self):
        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_force_check_chat_text"),
                    icon="msg_retry",
                    subtext=self._t("menu_force_check_chat_subtext"),
                    on_click=lambda payload: self._on_force_check_current_chat_clicked(
                        payload
                    ),
                    priority=1000,
                )
            )
            self._chat_force_check_menu_item_id = item_id
        except Exception as e:
            self._chat_force_check_menu_item_id = None
            self.log(f"Failed to register chat action menu item: {e}")

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_go_to_streak_start_text"),
                    icon="other_chats",
                    subtext=self._t("menu_go_to_streak_start_subtext"),
                    on_click=lambda payload: self._on_go_to_streak_start_clicked(
                        payload
                    ),
                    priority=999,
                )
            )
            self._chat_go_to_streak_start_menu_item_id = item_id
        except Exception as e:
            self._chat_go_to_streak_start_menu_item_id = None
            self.log(f"Failed to register go-to-streak-start menu item: {e}")

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_upgrade_service_messages_text"),
                    subtext=self._t("menu_upgrade_service_messages_subtext"),
                    icon="msg_settings",
                    on_click=lambda payload: (
                        self._on_toggle_upgrade_service_messages_clicked(payload)
                    ),
                    priority=998,
                )
            )
            self._chat_upgrade_service_messages_menu_item_id = item_id
        except Exception as e:
            self._chat_upgrade_service_messages_menu_item_id = None
            self.log(f"Failed to register upgrade-service-messages menu item: {e}")

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_restore_streak_text"),
                    subtext=self._t("menu_restore_streak_subtext"),
                    icon="msg_reactions",
                    on_click=lambda payload: self._on_restore_streak_clicked(payload),
                    priority=997,
                )
            )
            self._chat_restore_streak_menu_item_id = item_id
        except Exception as e:
            self._chat_restore_streak_menu_item_id = None
            self.log(f"Failed to register restore-streak menu item: {e}")

        if not DEBUG_MODE:
            return

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_debug_create_streak_text"),
                    subtext=self._t("menu_debug_create_streak_subtext"),
                    on_click=lambda payload: self._on_debug_create_streak_clicked(
                        payload
                    ),
                    priority=996,
                )
            )
            self._chat_debug_create_streak_menu_item_id = item_id
        except Exception as e:
            self._chat_debug_create_streak_menu_item_id = None
            self.log(f"Failed to register debug-create menu item: {e}")

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_debug_kill_streak_text"),
                    subtext=self._t("menu_debug_kill_streak_subtext"),
                    on_click=lambda payload: self._on_debug_kill_streak_clicked(
                        payload
                    ),
                    priority=995,
                )
            )
            self._chat_debug_kill_streak_menu_item_id = item_id
        except Exception as e:
            self._chat_debug_kill_streak_menu_item_id = None
            self.log(f"Failed to register debug-kill menu item: {e}")

        try:
            item_id = self.add_menu_item(
                MenuItemData(
                    menu_type=MenuItemType.CHAT_ACTION_MENU,
                    text=self._t("menu_debug_upgrade_streak_text"),
                    subtext=self._t("menu_debug_upgrade_streak_subtext"),
                    on_click=lambda payload: self._on_debug_upgrade_streak_clicked(
                        payload
                    ),
                    priority=995,
                )
            )
            self._chat_debug_upgrade_streak_menu_item_id = item_id
        except Exception as e:
            self._chat_debug_upgrade_streak_menu_item_id = None
            self.log(f"Failed to register debug-upgrade menu item: {e}")

    def _remove_menu_item_safely(self, item_id: Optional[str], item_name: str):
        if not item_id:
            return

        try:
            self.remove_menu_item(item_id)
        except Exception as e:
            self.log(f"Failed to remove {item_name} menu item: {e}")

    def _unregister_chat_action_menu_items(self):
        self._remove_menu_item_safely(
            getattr(self, "_chat_force_check_menu_item_id", None), "force-check"
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_go_to_streak_start_menu_item_id", None),
            "go-to-streak-start",
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_upgrade_service_messages_menu_item_id", None),
            "upgrade-service-messages",
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_restore_streak_menu_item_id", None),
            "restore-streak",
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_debug_create_streak_menu_item_id", None),
            "debug-create",
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_debug_kill_streak_menu_item_id", None), "debug-kill"
        )
        self._remove_menu_item_safely(
            getattr(self, "_chat_debug_upgrade_streak_menu_item_id", None),
            "debug-upgrade",
        )
        self._chat_force_check_menu_item_id = None
        self._chat_go_to_streak_start_menu_item_id = None
        self._chat_upgrade_service_messages_menu_item_id = None
        self._chat_restore_streak_menu_item_id = None
        self._chat_upgrade_service_state_cache = {}
        self._chat_debug_create_streak_menu_item_id = None
        self._chat_debug_kill_streak_menu_item_id = None
        self._chat_debug_upgrade_streak_menu_item_id = None

    def _extract_dialog_id_from_menu_payload(self, payload: Any) -> Optional[int]:
        if payload is None:
            return None

        def parse_dialog_id(value: Any) -> Optional[int]:
            if value is None:
                return None

            try:
                dialog_id = int(value)
            except Exception:
                return None

            return dialog_id or None

        if isinstance(payload, dict):
            payload_getter = payload.get
        else:
            try:
                payload_getter = payload.get
            except Exception:
                payload_getter = None

        if payload_getter is not None:
            for key in MENU_PAYLOAD_DIALOG_KEYS:
                dialog_id = parse_dialog_id(payload_getter(key))
                if dialog_id is not None:
                    return dialog_id

            for key in MENU_PAYLOAD_FRAGMENT_KEYS:
                value = payload_getter(key)
                if value is None:
                    continue

                try:
                    dialog_id = parse_dialog_id(value.getDialogId())
                except Exception:
                    dialog_id = None

                if dialog_id is not None:
                    return dialog_id

        try:
            return parse_dialog_id(payload.getDialogId())
        except Exception:
            return None

    def _extract_chat_activity_from_menu_payload(
        self, payload: Any
    ) -> Optional[ChatActivity]:
        if payload is None:
            return None

        candidates: list[Any] = [payload]
        if isinstance(payload, dict):
            payload_getter = payload.get
        else:
            try:
                payload_getter = payload.get
            except Exception:
                payload_getter = None

        if payload_getter is not None:
            for key in MENU_PAYLOAD_FRAGMENT_KEYS:
                value = payload_getter(key)
                if value is not None:
                    candidates.append(value)

        for value in candidates:
            if value is None:
                continue

            try:
                dialog_id = int(value.getDialogId())
                if dialog_id != 0:
                    return cast("ChatActivity", value)
            except Exception:
                continue

        return None

    def _extract_current_account_from_menu_payload(self, payload: Any) -> int:
        candidates = [self._extract_chat_activity_from_menu_payload(payload)]

        try:
            candidates.append(get_last_fragment())
        except Exception:
            pass

        for candidate in candidates:
            if candidate is None:
                continue

            try:
                return int(candidate.getCurrentAccount())
            except Exception:
                pass

            try:
                return int(getattr(candidate, "currentAccount"))
            except Exception:
                pass

        try:
            return int(get_account_instance().getCurrentAccount())
        except Exception:
            return 0

    def _can_force_check_dialog_id(self, dialog_id: int) -> bool:
        return self._get_force_check_dialog_restriction_key(dialog_id) is None

    def _get_force_check_dialog_restriction_key(self, dialog_id: int) -> Optional[str]:
        if dialog_id <= 0:
            return "info_private_chat_only"

        if not DialogObject.isUserDialog(dialog_id):
            return "info_private_chat_only"

        if dialog_id in get_authorized_user_ids():
            return "info_action_not_available_for_your_other_account"

        if UserObject.isReplyUser(dialog_id):
            return "info_action_not_available_for_saved_messages"

        if UserObject.isService(dialog_id):
            return "info_private_chat_only"

        user = self._resolve_dialog_user(get_messages_controller(), dialog_id)

        if user is None:
            return None

        if UserObject.isUserSelf(user):
            return "info_action_not_available_for_saved_messages"

        if UserObject.isDeleted(user):
            return "info_action_not_available_for_deleted_users"

        if UserObject.isBot(user):
            return "info_action_not_available_for_bots"

        if UserObject.isReplyUser(user):
            return "info_action_not_available_for_saved_messages"

        return None

    def _show_force_check_dialog_restriction(self, dialog_id: int):
        key = self._get_force_check_dialog_restriction_key(dialog_id)
        if key is None:
            key = "info_private_chat_only"
        self._show_info(self._t(key))

    def _find_first_message_id_in_range(
        self, peer: TLRPC.InputPeer, start_ts: int, end_ts: int
    ) -> Optional[int]:
        done = threading.Event()
        state = {
            "offset_id": 0,
            "offset_date": int(end_ts),
            "first_id": 0,
            "first_date": 0,
        }

        def load():
            req = TLRPC.TL_messages_getHistory()
            req.peer = peer
            req.offset_id = int(state["offset_id"])
            req.offset_date = int(state["offset_date"])
            req.limit = 100

            class Delegate(dynamic_proxy(RequestDelegate)):
                def run(self, response: TLObject, error: TLRPC.TL_error | None) -> None:
                    if error is not None:
                        done.set()
                        log(error)
                        return

                    response = cast("TLRPC.messages_Messages", response)
                    msgs = response.messages

                    if msgs is None or msgs.size() == 0:
                        done.set()
                        return

                    for i in range(msgs.size()):
                        message = msgs.get(i)
                        message_date = int(message.date)

                        if not (start_ts <= message_date < end_ts):
                            continue

                        message_id = int(message.id)
                        current_first_date = int(state["first_date"])
                        current_first_id = int(state["first_id"])

                        if (
                            current_first_id == 0
                            or message_date < current_first_date
                            or (
                                message_date == current_first_date
                                and message_id < current_first_id
                            )
                        ):
                            state["first_id"] = message_id
                            state["first_date"] = message_date

                    oldest = msgs.get(msgs.size() - 1)
                    oldest_date = int(oldest.date)
                    state["offset_id"] = int(oldest.id)
                    state["offset_date"] = oldest_date

                    if oldest_date >= start_ts:
                        load()
                    else:
                        done.set()

            get_connections_manager().sendRequest(req, Delegate())

        load()
        done.wait(timeout=60)

        first_id = int(state["first_id"])
        if first_id <= 0:
            return None

        return first_id

    def _on_force_check_current_chat_clicked(self, payload: Any):
        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return

        dialog_id = self._extract_dialog_id_from_menu_payload(payload)

        if dialog_id is None:
            self.log(f"Force check chat click payload missing dialog id: {payload}")
            self._show_error(self._t("err_cannot_detect_current_chat"))
            return

        if not self._can_force_check_dialog_id(dialog_id):
            self._show_force_check_dialog_restriction(dialog_id)
            return

        if not self._try_start_force_check("info_force_check_started_chat"):
            return

        def worker():
            try:
                day_progress_state = {
                    "last_reported": 0,
                }

                def on_day_progress(
                    progress_user_id: int,
                    day_checked: int,
                    day_offset: int,
                    is_active: bool,
                    include_today: bool,
                ):
                    if progress_user_id != dialog_id:
                        return

                    if day_progress_state["last_reported"] == day_checked and is_active:
                        return

                    day_progress_state["last_reported"] = day_checked
                    mode, state = self._force_check_day_labels(include_today, is_active)
                    message = self._t(
                        "force_check_day_progress_chat",
                        days_checked=day_checked,
                        day_offset=day_offset,
                        state=state,
                        mode=mode,
                    )
                    self.log(message)
                    self._show_info(message)

                checked, updated, removed, unchanged = (
                    self.streaks.force_check_user_ids(
                        [dialog_id],
                        on_day_progress=on_day_progress,
                    )
                )
                self._patch_cached_users_streak_emoji_status()
                summary = self._t(
                    "force_check_summary_chat",
                    checked=checked,
                    updated=updated,
                    removed=removed,
                    unchanged=unchanged,
                )
                self.log(summary)
                self._show_success(summary)
            except Exception as e:
                self.log(f"Force check failed for chat {dialog_id}: {e}")
                self._show_error(self._t("err_force_check_failed_logs"))
            finally:
                self._finish_force_check()

        threading.Thread(target=worker, daemon=True).start()

    def _on_go_to_streak_start_clicked(self, payload: Any):
        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return

        dialog_id = self._extract_dialog_id_from_menu_payload(payload)

        if dialog_id is None:
            self.log(f"Go-to-streak-start click payload missing dialog id: {payload}")
            self._show_error(self._t("err_cannot_detect_current_chat"))
            return

        if not self._can_force_check_dialog_id(dialog_id):
            self._show_force_check_dialog_restriction(dialog_id)
            return

        record = self.users_db.get_user(dialog_id)
        if record is None:
            self._show_info(self._t("info_no_streak_record_for_chat"))
            return

        chat_activity = self._extract_chat_activity_from_menu_payload(payload)
        if chat_activity is None:
            self.log(f"Go-to-streak-start payload missing ChatActivity: {payload}")
            self._show_error(self._t("err_cannot_open_chat_context"))
            return

        start_date = int(record.started_at)
        end_date = start_date + SECONDS_IN_DAY
        self._show_info(self._t("info_searching_streak_start_message"))

        def worker():
            message_id: Optional[int] = None

            try:
                peer = get_messages_controller().getInputPeer(dialog_id)  # ty:ignore[no-matching-overload]
                if peer is not None:
                    message_id = self._find_first_message_id_in_range(
                        peer, start_date, end_date
                    )
            except Exception as e:
                self.log(f"Go-to-streak-start lookup failed for {dialog_id}: {e}")

            def jump():
                try:
                    if message_id is not None and message_id > 0:
                        try:
                            chat_activity.scrollToMessageId(
                                message_id, 0, True, 0, True, 0
                            )
                            self._show_success(
                                self._t("ok_jumped_to_streak_start_message")
                            )
                            return
                        except Exception as e:
                            self.log(
                                f"Go-to-streak-start scroll failed for {dialog_id}: {e}"
                            )

                    chat_activity.jumpToDate(start_date)
                    self._show_info(self._t("info_exact_start_message_not_found"))
                except Exception as e:
                    self.log(f"Go-to-streak-start failed for {dialog_id}: {e}")
                    self._show_error(self._t("err_failed_jump_to_streak_start"))

            run_on_ui_thread(jump)

        threading.Thread(target=worker, daemon=True).start()

    def _on_toggle_upgrade_service_messages_clicked(self, payload: Any):
        dialog_id = self._extract_dialog_id_from_menu_payload(payload)

        if dialog_id is None:
            self._show_error(self._t("err_cannot_detect_current_chat"))
            return

        if not self._can_force_check_dialog_id(dialog_id):
            self._show_force_check_dialog_restriction(dialog_id)
            return

        enabled = not self._is_chat_upgrade_service_enabled(dialog_id)
        self._set_chat_upgrade_service_state(dialog_id, enabled)

        if enabled:
            self._show_success(self._t("ok_upgrade_service_messages_enabled"))
        else:
            self._show_success(self._t("ok_upgrade_service_messages_disabled"))

    def _restore_streak(
        self,
        account: int,
        dialog_id: int,
        event_day: Optional[int] = None,
        send_service_message: bool = True,
    ) -> bool:
        current = self.users_db.get_user(dialog_id, include_authorized=True)
        if current is None:
            return False

        restore_day = event_day
        if not current.can_restore(restore_day):
            if not current.can_restore():
                return False
            restore_day = None

        restored = UserRecord.from_dict(current.to_dict())
        if restored is None or not restored.revive(restore_day):
            return False

        self._remember_dialog_account(dialog_id, account)
        self.users_db.set_user_record(restored)
        self._streak_dead_state[dialog_id] = restored.should_die()
        if send_service_message:
            self._maybe_send_restore_service_message(account, dialog_id, restore_day)

        try:
            self._refresh_streak_emoji_for_peer(dialog_id)
        except Exception as e:
            self.log(
                f"Failed to refresh restored streak emoji for peer {dialog_id}: {e}"
            )

        try:
            self._rerender_dialog_cells()
        except Exception as e:
            self.log(f"Failed to rerender dialogs after streak restore: {e}")

        return True

    def _revive_streak_from_dialog_id(self, dialog_id: int, account: int = -1) -> bool:
        try:
            dialog_id = int(dialog_id)
        except Exception:
            return False

        if dialog_id <= 0:
            return False

        try:
            restored = self._restore_streak(account, dialog_id)
        except Exception as e:
            self.log(f"Failed to restore streak for chat {dialog_id}: {e}")
            restored = False

        if restored:
            self._show_success(self._t("ok_streak_restored"))
            return True

        self._show_info(self._t("info_streak_restore_unavailable"))
        return False

    def _on_restore_streak_clicked(self, payload: Any):
        dialog_id = self._extract_dialog_id_from_menu_payload(payload)

        if dialog_id is None:
            self._show_error(self._t("err_cannot_detect_current_chat"))
            return

        if not self._can_force_check_dialog_id(dialog_id):
            self._show_force_check_dialog_restriction(dialog_id)
            return

        account = self._extract_current_account_from_menu_payload(payload)
        self._revive_streak_from_dialog_id(dialog_id, account)

    def _extract_debug_target_dialog_id(self, payload: Any) -> Optional[int]:
        if not DEBUG_MODE:
            self._show_info(self._t("info_debug_mode_disabled"))
            return None

        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return None

        dialog_id = self._extract_dialog_id_from_menu_payload(payload)
        if dialog_id is None:
            self._show_error(self._t("err_cannot_detect_current_chat"))
            return None

        if not self._can_force_check_dialog_id(dialog_id):
            self._show_force_check_dialog_restriction(dialog_id)
            return None

        return dialog_id

    def _make_alive_record(self, dialog_id: int, target_length: int) -> UserRecord:
        target_length = max(int(target_length), 1)
        today = TimeUtils.get_stripped_timestamp()
        return UserRecord(
            user_id=int(dialog_id),
            started_at=today - ((target_length - 1) * SECONDS_IN_DAY),
            freezes_at=today + SECONDS_IN_DAY,
            last_sended_at=today,
            last_received_at=today,
            restored_days=[],
        )

    def _persist_debug_record(
        self,
        payload: Any,
        before: Optional[dict[str, int]],
        record: UserRecord,
    ):
        self.users_db.set_user_record(record)
        self._patch_cached_users_streak_emoji_status()
        after_record = self.users_db.get_user(record.user_id)
        after = self._record_state(after_record)
        self._maybe_notify_streak_transition(
            self._extract_current_account_from_menu_payload(payload),
            record.user_id,
            before,
            after,
            TimeUtils.get_stripped_timestamp(),
        )

        if after_record is not None:
            self._streak_dead_state[int(record.user_id)] = bool(
                after_record.should_die()
            )

    def _on_debug_create_streak_clicked(self, payload: Any):
        dialog_id = self._extract_debug_target_dialog_id(payload)
        if dialog_id is None:
            return

        before = self._record_state(self.users_db.get_user(dialog_id))
        record = self._make_alive_record(dialog_id, 3)
        self._persist_debug_record(payload, before, record)
        self._show_success(self._t("ok_debug_streak_set_3"))

    def _on_debug_kill_streak_clicked(self, payload: Any):
        dialog_id = self._extract_debug_target_dialog_id(payload)
        if dialog_id is None:
            return

        current = self.users_db.get_user(dialog_id)
        if current is None or current.should_die() or current.get_length() < 3:
            current = self._make_alive_record(dialog_id, 3)

        before = self._record_state(current)
        today = TimeUtils.get_stripped_timestamp()
        dead_record = UserRecord(
            user_id=int(dialog_id),
            started_at=int(current.started_at),
            freezes_at=today - SECONDS_IN_DAY,
            last_sended_at=TimeUtils.get_local_day_offset(-2),
            last_received_at=TimeUtils.get_local_day_offset(-2),
            restored_days=list(current.restored_days),
        )
        self._persist_debug_record(payload, before, dead_record)
        self._show_success(self._t("ok_debug_streak_marked_dead"))

    def _on_debug_upgrade_streak_clicked(self, payload: Any):
        dialog_id = self._extract_debug_target_dialog_id(payload)
        if dialog_id is None:
            return

        current = self.users_db.get_user(dialog_id)
        if current is None or current.should_die():
            current = self._make_alive_record(dialog_id, 3)

        before = self._record_state(current)
        current_length = max(int(current.get_length()), 3)

        target_length = StreakLevels.get_next_level_length(current_length)

        if target_length is None:
            self._show_info(self._t("info_debug_streak_already_max"))
            return

        upgraded = self._make_alive_record(dialog_id, target_length)
        self._persist_debug_record(payload, before, upgraded)
        self._show_success(self._t("ok_debug_streak_upgraded", days=target_length))

    def _resolve_dialog_user(
        self, controller: MessagesController, dialog_id: int
    ) -> Optional[TLRPC.User]:
        user = None

        try:
            user = controller.getUser(Long(dialog_id))
        except Exception:
            user = None

        if user is not None:
            return user

        try:
            users = controller.getUsers()
            if users is not None:
                user = users.get(Long(dialog_id))
        except Exception:
            user = None

        return user

    def _collect_real_private_dialog_user_ids(self) -> list[int]:
        result: set[int] = set()
        controller = get_messages_controller()
        dialogs = getattr(controller, "allDialogs", None)

        if dialogs is None:
            dialogs = controller.getDialogs(0)

        if dialogs is None:
            return []

        self_user_ids = set(get_authorized_user_ids())

        for i in range(dialogs.size()):
            dialog = dialogs.get(i)

            if dialog is None:
                continue

            dialog_id = int(getattr(dialog, "id", 0))

            if dialog_id == 0:
                try:
                    DialogObject.initDialog(dialog)
                    dialog_id = int(getattr(dialog, "id", 0))
                except Exception:
                    dialog_id = 0

            if dialog_id == 0:
                try:
                    peer = getattr(dialog, "peer", None)
                    if peer is not None:
                        dialog_id = int(DialogObject.getPeerDialogId(peer))
                    else:
                        dialog_id = 0
                except Exception:
                    dialog_id = 0

            if dialog_id <= 0:
                continue

            if not DialogObject.isUserDialog(dialog_id):
                continue

            if dialog_id in self_user_ids or UserObject.isReplyUser(dialog_id):
                continue

            if UserObject.isService(dialog_id):
                continue

            user = self._resolve_dialog_user(controller, dialog_id)

            if user is not None:
                if UserObject.isUserSelf(user):
                    continue

                if UserObject.isDeleted(user):
                    continue

                if UserObject.isBot(user):
                    continue

                if UserObject.isReplyUser(user):
                    continue

            result.add(dialog_id)

        return sorted(result)

    def _on_force_check_all_chats_clicked(self):
        if not hasattr(self, "users_db"):
            self._show_error(self._t("err_users_db_not_ready"))
            return

        if not self._try_start_force_check("info_force_check_started_all"):
            return

        def worker():
            try:
                user_ids = self._collect_real_private_dialog_user_ids()
                total = len(user_ids)
                self.log(f"Force check targets: {total}")

                progress_lock = threading.Lock()
                progress_state = {
                    "last_bucket": -1,
                    "last_checked": -1,
                    "checked_chats": 0,
                    "total": total,
                }
                day_progress_state: dict[int, int] = {}
                peer_name_cache: dict[int, str] = {}

                def on_progress(
                    checked: int,
                    progress_total: int,
                    updated: int,
                    removed: int,
                    unchanged: int,
                ):
                    with progress_lock:
                        total_local = int(progress_total)
                        checked_local = int(checked)
                        progress_state["total"] = total_local
                        progress_state["checked_chats"] = checked_local

                        if total_local <= 0:
                            if progress_state["last_checked"] != 0:
                                progress_state["last_checked"] = 0
                                self.log(self._t("force_check_progress_zero"))
                            return

                        bucket = (checked_local * 10) // total_local

                        if (
                            checked_local != total_local
                            and checked_local > 0
                            and bucket == progress_state["last_bucket"]
                        ):
                            return

                        progress_state["last_bucket"] = bucket
                        progress_state["last_checked"] = checked_local

                    progress_message = self._t(
                        "force_check_progress",
                        checked=checked_local,
                        total=total_local,
                        updated=updated,
                        removed=removed,
                        unchanged=unchanged,
                    )
                    self.log(progress_message)
                    self._show_info(progress_message)

                def on_day_progress(
                    progress_user_id: int,
                    day_checked: int,
                    day_offset: int,
                    is_active: bool,
                    include_today: bool,
                ):
                    if (
                        day_progress_state.get(progress_user_id, 0) == int(day_checked)
                        and is_active
                    ):
                        return

                    day_progress_state[progress_user_id] = int(day_checked)
                    mode, state = self._force_check_day_labels(include_today, is_active)
                    checked_chats = int(progress_state.get("checked_chats", 0))
                    total_chats = int(progress_state.get("total", total))
                    peer_name = peer_name_cache.get(progress_user_id)

                    if peer_name is None:
                        peer_name = self._resolve_peer_name(progress_user_id)
                        peer_name_cache[progress_user_id] = peer_name

                    day_message = self._t(
                        "force_check_day_progress_all",
                        days_checked=day_checked,
                        peer_name=peer_name,
                        checked_chats=checked_chats,
                        total_chats=total_chats,
                        day_offset=day_offset,
                        state=state,
                        mode=mode,
                    )
                    self.log(day_message)
                    self._show_info(day_message)

                checked, updated, removed, unchanged = (
                    self.streaks.force_check_user_ids(
                        user_ids,
                        on_progress=on_progress,
                        on_day_progress=on_day_progress,
                    )
                )
                self._patch_cached_users_streak_emoji_status()
                summary = self._t(
                    "force_check_summary_all",
                    checked=checked,
                    updated=updated,
                    removed=removed,
                    unchanged=unchanged,
                )
                self.log(summary)
                self._show_success(summary)
            except Exception as e:
                self.log(f"Force check failed: {e}")
                self._show_error(self._t("err_force_check_failed_logs"))
            finally:
                self._finish_force_check()

        threading.Thread(target=worker, daemon=True).start()

    def _try_start_force_check(self, started_message_key: str) -> bool:
        with self._force_check_lock:
            if self._force_check_running:
                self._show_info(self._t("info_force_check_already_running"))
                return False
            self._force_check_running = True

        self._show_info(self._t(started_message_key))
        return True

    def _finish_force_check(self):
        with self._force_check_lock:
            self._force_check_running = False

    def _get_tl_name(self, value: Any) -> str:
        try:
            return cast("str", value.getClass().getSimpleName())
        except Exception:
            return cast("str", value.__class__.__name__)

    def _get_self_user_id(self, account: int) -> int:
        account = int(account)
        cache = cast("dict[int, int]", getattr(self, "_self_user_ids", {}))
        cached_id = cache.get(account, 0)

        if cached_id > 0:
            return cached_id

        try:
            user_config = UserConfig.getInstance(account)
            user_id = int(user_config.getClientUserId())

            if user_id > 0:
                cache[account] = user_id
                self._self_user_ids = cache

            return user_id
        except Exception:
            return 0

    def _extract_private_peer_id(self, peer: Any) -> Optional[int]:
        if peer is None:
            return None

        if isinstance(peer, int):
            return peer if peer > 0 else None

        try:
            class_name = cast("str", peer.getClass().getName())
        except Exception:
            class_name = ""

        if class_name in ("java.lang.Integer", "java.lang.Long"):
            try:
                peer_id = int(peer)
                if peer_id > 0:
                    return peer_id
            except Exception:
                pass

        user_id = getattr(peer, "user_id", None)

        if user_id is None:
            return None

        try:
            user_id = int(user_id)
        except Exception:
            return None

        if user_id <= 0:
            return None

        # Explicitly reject groups/channels.
        if int(getattr(peer, "chat_id", 0)) != 0:
            return None

        if int(getattr(peer, "channel_id", 0)) != 0:
            return None

        return user_id

    def _query_cached_day_activity_flags(
        self, dialog_id: int, day_start: int, day_end: int
    ) -> Optional[tuple[bool, bool]]:
        if dialog_id <= 0:
            return None

        try:
            storage = get_account_instance().getMessagesStorage()
            db = storage.getDatabase()
        except Exception:
            return None

        table_name = _resolve_day_check_messages_table_name(db)
        if table_name is None:
            return None

        cursor = None

        try:
            cursor = db.queryFinalized(
                String(
                    f"SELECT "
                    f"MAX(CASE WHEN out = 1 THEN 1 ELSE 0 END), "
                    f"MAX(CASE WHEN out = 0 THEN 1 ELSE 0 END) "
                    f"FROM {table_name} "
                    f"WHERE uid = ? AND date >= ? AND date < ?"
                ),
                Integer(dialog_id),
                Integer(day_start),
                Integer(day_end),
            )

            if cursor is None or not cursor.next():
                return None

            has_out = (0 if cursor.isNull(0) else int(cursor.intValue(0))) == 1
            has_in = (0 if cursor.isNull(1) else int(cursor.intValue(1))) == 1

            return (has_out, has_in)
        except Exception:
            return None
        finally:
            if cursor is not None:
                try:
                    cursor.dispose()
                except Exception:
                    pass

    def _repair_record_from_cached_today_activity(
        self, peer_id: int, event_day: Optional[int] = None
    ):
        current = self.users_db.get_user(peer_id, include_authorized=True)
        if current is None:
            return

        day = (
            TimeUtils.get_stripped_timestamp()
            if event_day is None
            else TimeUtils.strip_timestamp(event_day)
        )
        day_end = int(day + SECONDS_IN_DAY)

        flags = self._query_cached_day_activity_flags(peer_id, day, day_end)
        if flags is None:
            return

        has_out, has_in = flags
        if not has_out and not has_in:
            return

        updated = UserRecord.from_dict(current.to_dict())
        if updated is None:
            return

        changed = False
        if has_out:
            changed = updated.update_from(SenderType.SELF, day) or changed
        if has_in:
            changed = updated.update_from(SenderType.PEER, day) or changed

        if changed:
            self.users_db.set_user_record(updated)

    def _extract_private_peer_id_from_send_params(self, params: Any) -> Optional[int]:
        candidates: list[Any] = []

        direct_attrs = (
            "peer",
            "peer_id",
            "dialogId",
            "dialog_id",
            "uid",
            "user_id",
        )

        for attr_name in direct_attrs:
            try:
                value = getattr(params, attr_name, None)
            except Exception:
                value = None
            if value is not None:
                candidates.append(value)

        nested_roots = ("messageObject", "message", "obj", "messageOwner")
        for root_name in nested_roots:
            try:
                root = getattr(params, root_name, None)
            except Exception:
                root = None

            if root is None:
                continue

            candidates.append(root)

            for attr_name in ("peer", "peer_id", "dialogId", "dialog_id", "uid"):
                try:
                    value = getattr(root, attr_name, None)
                except Exception:
                    value = None
                if value is not None:
                    candidates.append(value)

            try:
                owner = getattr(root, "messageOwner", None)
            except Exception:
                owner = None

            if owner is not None:
                candidates.append(owner)
                for attr_name in ("peer_id", "from_id", "dialog_id", "dialogId"):
                    try:
                        value = getattr(owner, attr_name, None)
                    except Exception:
                        value = None
                    if value is not None:
                        candidates.append(value)

        for candidate in candidates:
            peer_id = self._extract_private_peer_id(candidate)
            if peer_id is not None and peer_id > 0:
                return peer_id

        for candidate in candidates:
            try:
                dialog_id = int(candidate)
            except Exception:
                continue

            if dialog_id <= 0:
                continue

            try:
                if DialogObject.isUserDialog(dialog_id):
                    return dialog_id
            except Exception:
                pass

        return None

    def _extract_private_dialog_id_from_message(self, message: Any) -> Optional[int]:
        candidates: list[Any] = [message]

        for attr_name in ("peer_id", "peer", "dialog_id", "dialogId", "user_id", "uid"):
            try:
                value = getattr(message, attr_name, None)
            except Exception:
                value = None
            if value is not None:
                candidates.append(value)

        try:
            owner = getattr(message, "messageOwner", None)
        except Exception:
            owner = None

        if owner is not None:
            candidates.append(owner)
            for attr_name in (
                "peer_id",
                "peer",
                "dialog_id",
                "dialogId",
                "user_id",
                "uid",
            ):
                try:
                    value = getattr(owner, attr_name, None)
                except Exception:
                    value = None
                if value is not None:
                    candidates.append(value)

        try:
            dialog_id = message.getDialogId()
        except Exception:
            dialog_id = None
        if dialog_id is not None:
            candidates.append(dialog_id)

        for candidate in candidates:
            peer_id = self._extract_private_peer_id(candidate)
            if peer_id is not None and peer_id > 0:
                return peer_id

        for candidate in candidates:
            try:
                dialog_id = int(candidate)
            except Exception:
                continue

            if dialog_id <= 0:
                continue

            try:
                if DialogObject.isUserDialog(dialog_id):
                    return dialog_id
            except Exception:
                pass

        return None

    def _extract_sender_id_from_message(self, message: Any) -> Optional[int]:
        candidates: list[Any] = []

        for attr_name in ("from_id", "sender_id", "fromId", "senderId"):
            try:
                value = getattr(message, attr_name, None)
            except Exception:
                value = None
            if value is not None:
                candidates.append(value)

        try:
            owner = getattr(message, "messageOwner", None)
        except Exception:
            owner = None

        if owner is not None:
            for attr_name in ("from_id", "sender_id", "fromId", "senderId"):
                try:
                    value = getattr(owner, attr_name, None)
                except Exception:
                    value = None
                if value is not None:
                    candidates.append(value)

        for candidate in candidates:
            sender_id = self._extract_private_peer_id(candidate)
            if sender_id is not None and sender_id > 0:
                return sender_id

        return None

    def _extract_message_text(self, message: Any) -> Optional[str]:
        roots = [message]

        try:
            owner = getattr(message, "messageOwner", None)
        except Exception:
            owner = None

        if owner is not None:
            roots.append(owner)

        for root in roots:
            for attr_name in (
                "message",
                "caption",
                "messageText",
                "text",
                "messageTextForReply",
            ):
                try:
                    value = getattr(root, attr_name, None)
                except Exception:
                    value = None

                if value is None:
                    continue

                try:
                    text = str(value).strip()
                except Exception:
                    continue

                if text:
                    return text

            for method_name in ("getMessageText", "getCaption", "getText"):
                try:
                    method = getattr(root, method_name, None)
                except Exception:
                    method = None

                if method is None:
                    continue

                try:
                    value = method()
                except Exception:
                    continue

                if value is None:
                    continue

                try:
                    text = str(value).strip()
                except Exception:
                    continue

                if text:
                    return text

        return None

    def _consume_message(self, account: int, message: Any):
        try:
            message_text = self._extract_message_text(message)
            peer_id = self._extract_private_dialog_id_from_message(message)

            raw_message_date = getattr(message, "date", int(time.time()))
            message_date = int(raw_message_date)
            event_day = TimeUtils.strip_timestamp(message_date)
            from_id = self._extract_sender_id_from_message(message)
            message_out = bool(getattr(message, "out", False))

            if self._is_tagged_service_message_text(message_text):
                self.log(
                    f"Consume tagged message: account={account}, class={self._get_tl_name(message)}, text={self._safe_log_value(message_text)}, peer={peer_id}, from_id={from_id}, out={message_out}, date={message_date}, event_day={event_day}"
                )

            if from_id is None and message_out:
                self_user_id = self._get_self_user_id(account)
                if self_user_id > 0:
                    from_id = self_user_id
            elif from_id is None and peer_id is not None:
                from_id = peer_id

            if self._is_restore_service_message_text(message_text) and peer_id is None:
                self.log(
                    f"Restore service message without resolved dialog id: account={account}, from_id={from_id}, event_day={event_day}, message_class={self._get_tl_name(message)}, message={self._safe_log_value(message)}"
                )

            if peer_id is None:
                if self._is_tagged_service_message_text(message_text):
                    self.log(
                        f"Skip tagged message without peer: account={account}, class={self._get_tl_name(message)}"
                    )
                return

            if self._is_restore_service_message_text(message_text):
                self._maybe_consume_restore_service_message(
                    account, peer_id, from_id, event_day
                )
                return

            if self._is_internal_service_message_text(message_text):
                self.log(
                    f"Skip internal service message: account={account}, peer={peer_id}, text={self._safe_log_value(message_text)}"
                )
                return

            self_user_id = self._get_self_user_id(account)

            if self_user_id > 0 and peer_id == self_user_id:
                return

            sender_type = SenderType.PEER
            if from_id is not None and self_user_id > 0:
                sender_type = (
                    SenderType.SELF if from_id == self_user_id else SenderType.PEER
                )
            elif message_out:
                sender_type = SenderType.SELF

            self._notify_for_dialog_event(account, peer_id, sender_type, event_day)
        except Exception as e:
            self.log(
                f"_consume_message failed: account={account}, class={self._get_tl_name(message)}, error={e}"
            )

    def _consume_update(self, account: int, update_name: str, update: Any):
        if update_name in ("TL_updateNewMessage", "TL_updateNewChannelMessage"):
            try:
                message = getattr(update, "message", None)
            except Exception as e:
                self.log(
                    f"Failed to get message from {update_name} on account {account}: {e}"
                )
                return

            self._log_service_update_trace(
                "Incoming update", account, update_name, update
            )

            if message is None:
                self.log(f"{update_name} has no message payload on account {account}")
                return

            self._consume_message(account, message)

            return

        if update_name in ("TL_updateEditMessage", "TL_updateEditChannelMessage"):
            try:
                message = getattr(update, "message", None)
            except Exception as e:
                self.log(
                    f"Failed to get edited message from {update_name} on account {account}: {e}"
                )
                return

            self._log_service_update_trace(
                "Incoming edit update", account, update_name, update
            )

            if message is None:
                return

            try:
                message_text = self._extract_message_text(message)
            except Exception as e:
                self.log(
                    f"Failed to extract edited message text from {update_name} on account {account}: {e}"
                )
                return

            if self._is_restore_service_message_text(message_text):
                self._consume_message(account, message)
            elif self._is_tagged_service_message_text(message_text):
                self.log(
                    f"Skip non-restore tagged edit update: account={account}, update={update_name}, text={self._safe_log_value(message_text)}"
                )

            return

        if update_name == "TL_updateShortMessage":
            try:
                peer_id = int(getattr(update, "user_id", 0))
            except Exception as e:
                self.log(
                    f"Failed to read peer from TL_updateShortMessage on account {account}: {e}"
                )
                return

            if peer_id <= 0:
                self._log_service_update_trace(
                    "Skip short message with invalid peer",
                    account,
                    update_name,
                    update,
                )
                return

            self_user_id = self._get_self_user_id(account)

            if self_user_id > 0 and peer_id == self_user_id:
                return

            message_text = getattr(update, "message", None)
            message_date = int(getattr(update, "date", int(time.time())))
            event_day = TimeUtils.strip_timestamp(message_date)
            from_id = (
                self_user_id
                if bool(getattr(update, "out", False)) and self_user_id > 0
                else peer_id
            )

            if self._is_tagged_service_message_text(message_text):
                self.log(
                    f"Consume short tagged update: account={account}, peer={peer_id}, from_id={from_id}, out={bool(getattr(update, 'out', False))}, date={message_date}, event_day={event_day}, text={self._safe_log_value(message_text)}"
                )

            if self._is_restore_service_message_text(message_text):
                self._maybe_consume_restore_service_message(
                    account, peer_id, from_id, event_day
                )
                return

            if self._is_internal_service_message_text(message_text):
                return

            sender_type = (
                SenderType.SELF
                if bool(getattr(update, "out", False))
                else SenderType.PEER
            )
            self._notify_for_dialog_event(account, peer_id, sender_type, event_day)
            return

        if update_name == "TL_updateShortChatMessage":
            return

        if update_name == "TL_updateShort":
            nested_update = getattr(update, "update", None)

            if nested_update is not None:
                nested_name = self._get_tl_name(nested_update)
                self._log_service_update_trace(
                    "Incoming short wrapper",
                    account,
                    nested_name,
                    nested_update,
                    extra=f"wrapper={update_name}",
                )
                try:
                    self._consume_update(account, nested_name, nested_update)
                except Exception as e:
                    self.log(
                        f"Failed to consume nested update {nested_name} from {update_name} on account {account}: {e}"
                    )

            return

        if update_name in ("TL_updates", "TL_updatesCombined"):
            updates = getattr(update, "updates", None)

            if updates is None:
                return

            for i in range(updates.size()):
                nested_update = updates.get(i)
                nested_name = self._get_tl_name(nested_update)
                self._log_service_update_trace(
                    "Incoming updates wrapper",
                    account,
                    nested_name,
                    nested_update,
                    extra=f"wrapper={update_name}, index={i}",
                )
                try:
                    self._consume_update(account, nested_name, nested_update)
                except Exception as e:
                    self.log(
                        f"Failed to consume nested update {nested_name} from {update_name}[{i}] on account {account}: {e}"
                    )

    def _patch_cached_users_streak_emoji_status(self):
        users = cast(
            "ConcurrentHashMap[Long, TLRPC.User]",
            get_private_field(get_messages_controller(), "users"),
        )
        users_iter = users.values().iterator()

        for i in range(users.size()):
            self._patch_user_streak_emoji_status(users_iter.next())

    def _patch_user_streak_emoji_status(self, user: TLRPC.User):
        user_record = self.users_db.get_user(user.id)

        if user_record is None:
            return

        length = int(user_record.get_length())
        if length < 3:
            return

        user.emoji_status = TLRPC.TL_emojiStatus()
        user.emoji_status.document_id = StreakLevels.COLD.value.document_id
        user.premium = True
        self._post_user_emoji_status_updated(user)
        self._post_emoji_status_interface_update()

    def _post_notification_to_active_accounts(
        self, notification_name: int, *args: Any, error_context: str
    ):
        def post():
            try:
                for i in range(UserConfig.MAX_ACCOUNT_COUNT):
                    cfg = UserConfig.getInstance(i)
                    if not cfg.isClientActivated():
                        continue
                    NotificationCenter.getInstance(i).postNotificationName(
                        notification_name, *args
                    )
            except Exception as e:
                self.log(f"Failed to post {error_context}: {e}")

        run_on_ui_thread(post)

    def _post_user_emoji_status_updated(self, user: Optional[TLRPC.User]):
        if user is None:
            return

        self._post_notification_to_active_accounts(
            NotificationCenter.userEmojiStatusUpdated,
            user,
            error_context="userEmojiStatusUpdated",
        )

    def _post_emoji_status_interface_update(self):
        self._post_notification_to_active_accounts(
            NotificationCenter.updateInterfaces,
            Integer(MessagesController.UPDATE_MASK_EMOJI_STATUS),
            error_context="emoji status updateInterfaces",
        )

    def _post_emoji_loaded_notification(self):
        self._post_notification_to_active_accounts(
            NotificationCenter.emojiLoaded,
            error_context="emojiLoaded",
        )

    def _post_dialogs_need_reload(self):
        self._post_notification_to_active_accounts(
            NotificationCenter.dialogsNeedReload,
            Boolean(True),
            error_context="dialogsNeedReload",
        )

    def _remember_consumed_tagged_message(self, message: Any) -> bool:
        try:
            message_text = self._extract_message_text(message)
        except Exception:
            message_text = None

        if not self._is_tagged_service_message_text(message_text):
            return False

        try:
            dialog_id = self._extract_private_dialog_id_from_message(message) or 0
        except Exception:
            dialog_id = 0

        try:
            message_id = int(getattr(message, "id", 0))
        except Exception:
            try:
                message_id = int(message.getId())
            except Exception:
                message_id = 0

        try:
            message_date = int(getattr(message, "date", 0))
        except Exception:
            message_date = 0

        dedupe_key = f"{dialog_id}:{message_id}:{message_date}:{self._safe_log_value(message_text, 96)}"
        seen = self._consumed_tagged_message_keys
        if dedupe_key in seen:
            return True

        seen.add(dedupe_key)
        if len(seen) > 512:
            self._consumed_tagged_message_keys = set(list(seen)[-256:])

        return False

    def hook_message_object_constructors(self):
        class MessageObjectCtorHook(MethodHook):
            def __init__(self, plugin: TgStreaksPlugin):
                self.plugin = plugin

            def after_hooked_method(self, param):
                try:
                    message_object = cast("MessageObject", param.thisObject)
                except Exception as e:
                    self.plugin.log(f"MessageObject ctor hook cast failed: {e}")
                    return

                try:
                    if self.plugin._remember_consumed_tagged_message(message_object):
                        return

                    text = self.plugin._extract_message_text(message_object)
                    if not self.plugin._is_tagged_service_message_text(text):
                        return

                    account = int(getattr(message_object, "currentAccount", -1))
                    self.plugin.log(
                        f"MessageObject ctor tagged message: account={account}, class={self.plugin._get_tl_name(message_object)}, text={self.plugin._safe_log_value(text)}"
                    )
                    self.plugin._consume_message(account, message_object)
                except Exception as e:
                    self.plugin.log(f"MessageObject ctor hook failed: {e}")

        try:
            self.hook_all_constructors(
                cast("Class", MessageObject), MessageObjectCtorHook(self)
            )
            self.log("MessageObject constructor hook attached")
        except Exception as e:
            self.log(f"Failed to hook MessageObject constructors: {e}")

    def hook_user_emoji_status_assign(self, patch_existing: bool):
        users = cast(
            "ConcurrentHashMap[Long, TLRPC.User]",
            get_private_field(get_messages_controller(), "users"),
        )
        users_hash = System.identityHashCode(users)

        def patch_user(user: TLRPC.User):
            self._patch_user_streak_emoji_status(user)

        class ConcurrentHashMap_putValHook(MethodHook):
            def __init__(self, plugin: TgStreaksPlugin, target_hash: int):
                self.plugin = plugin
                self.target_hash = target_hash

            def before_hooked_method(self, param):
                this_hash = System.identityHashCode(param.thisObject)

                if self.target_hash != this_hash:
                    return

                user = param.args[1]

                if user is None:
                    return

                user = cast("TLRPC.User", user)

                patch_user(user)

            def attach(plugin: TgStreaksPlugin, target_hash: int):
                # method name is randomized bc of proguard
                for method in users.getClass().getDeclaredMethods():
                    if method.getReturnType() != Object.getClass():
                        continue

                    if method.getParameterCount() != 3:
                        continue

                    if method.getParameterTypes() != [
                        Object.getClass(),
                        Object.getClass(),
                        Boolean.TYPE,
                    ]:
                        continue

                    self.hook_method(
                        method, ConcurrentHashMap_putValHook(self, target_hash)
                    )

        ConcurrentHashMap_putValHook.attach(self, users_hash)

        if not patch_existing:
            return

        users_iter = users.values().iterator()

        for i in range(users.size()):
            patch_user(users_iter.next())

    def _rerender_dialog_cells(self):
        def _invoke_title_action_for_all_dialogs_activities() -> int:
            def invalidate_dialogs_view_pages(fragment: Any) -> int:
                updated_pages = 0

                def invalidate_all_recycler_children(list_view: Any):
                    if list_view is None:
                        return

                    try:
                        child_count = int(list_view.getChildCount())
                    except Exception:
                        child_count = 0

                    for child_index in range(child_count):
                        try:
                            child = list_view.getChildAt(child_index)
                            if child is not None:
                                child.invalidate()
                        except Exception:
                            pass

                    recycler_groups = [
                        ("getCachedChildCount", "getCachedChildAt"),
                        ("getHiddenChildCount", "getHiddenChildAt"),
                        ("getAttachedScrapChildCount", "getAttachedScrapChildAt"),
                    ]

                    for count_method_name, get_method_name in recycler_groups:
                        try:
                            count_method = getattr(list_view, count_method_name)
                            get_method = getattr(list_view, get_method_name)
                            extra_count = int(count_method())
                        except Exception:
                            continue

                        for child_index in range(extra_count):
                            try:
                                child = get_method(child_index)
                                if child is not None:
                                    child.invalidate()
                            except Exception:
                                pass

                try:
                    pages = get_private_field(fragment, "viewPages")
                except Exception:
                    pages = None

                if pages is None:
                    return updated_pages

                try:
                    pages_len = int(len(pages))
                except Exception:
                    pages_len = 0

                for i in range(pages_len):
                    try:
                        page = pages[i]
                    except Exception:
                        continue

                    if page is None:
                        continue

                    try:
                        list_view = get_private_field(page, "listView")
                    except Exception:
                        list_view = None

                    if list_view is not None:
                        try:
                            list_view.stopScroll()
                        except Exception:
                            pass

                        try:
                            list_view.setItemViewCacheSize(0)
                        except Exception:
                            pass

                        try:
                            list_view.invalidateViews()
                        except Exception:
                            pass

                        try:
                            invalidate_all_recycler_children(list_view)
                        except Exception:
                            pass

                        try:
                            pool = list_view.getRecycledViewPool()
                            if pool is not None:
                                pool.clear()
                        except Exception:
                            pass

                        try:
                            adapter_to_reattach = list_view.getAdapter()
                        except Exception:
                            adapter_to_reattach = None

                        try:
                            if adapter_to_reattach is not None:
                                list_view.setAdapter(None)
                                list_view.setAdapter(adapter_to_reattach)
                        except Exception:
                            pass

                        try:
                            list_view.setItemViewCacheSize(2)
                        except Exception:
                            pass

                        try:
                            list_view.invalidate()
                            list_view.requestLayout()
                        except Exception:
                            pass

                    try:
                        adapter = get_private_field(page, "dialogsAdapter")
                    except Exception:
                        adapter = None

                    if adapter is not None:
                        try:
                            adapter.notifyDataSetChanged()
                        except Exception:
                            pass

                    updated_pages += 1

                return updated_pages

            launch = LaunchActivity.instance

            if launch is None:
                return 0

            applied = 0
            seen: set[int] = set()

            def apply_to_layout(field_name: str):
                nonlocal applied

                try:
                    layout = get_private_field(launch, field_name)
                    if layout is None:
                        return
                    stack = layout.getFragmentStack()
                    if stack is None:
                        return
                except Exception:
                    return

                try:
                    size = int(stack.size())
                except Exception:
                    size = 0

                for i in range(size):
                    try:
                        fragment = stack.get(i)
                    except Exception:
                        continue

                    if fragment is None:
                        continue

                    try:
                        class_name = cast("str", fragment.getClass().getName())
                    except Exception:
                        class_name = ""

                    if class_name != "org.telegram.ui.DialogsActivity":
                        continue

                    try:
                        fragment_hash = int(System.identityHashCode(fragment))
                    except Exception:
                        fragment_hash = id(fragment)

                    if fragment_hash in seen:
                        continue
                    seen.add(fragment_hash)

                    try:
                        action_bar = fragment.getActionBar()
                    except Exception:
                        action_bar = None

                    try:
                        if (
                            action_bar is not None
                            and not action_bar.isSearchFieldVisible()
                        ):
                            runnable = get_private_field(
                                action_bar, "titleActionRunnable"
                            )
                            if runnable is not None:
                                runnable.run()
                                applied += 1
                    except Exception:
                        pass

                    try:
                        invalidate_dialogs_view_pages(fragment)
                    except Exception:
                        pass

            apply_to_layout("actionBarLayout")
            apply_to_layout("rightActionBarLayout")
            apply_to_layout("layersActionBarLayout")
            return applied

        def post_reload():
            try:
                self._post_emoji_loaded_notification()
                self._post_dialogs_need_reload()
                self._post_emoji_status_interface_update()
                try:
                    AnimatedEmojiDrawable.updateAll()
                except Exception:
                    pass
                applied = _invoke_title_action_for_all_dialogs_activities()
                self.log(
                    f"Posted emoji status update via NotificationCenter; executed ActionBar title action for DialogsActivity count={applied}"
                )
            except Exception as e:
                self.log(f"Failed to post emoji status NotificationCenter update: {e}")

        threading.Timer(0.5, lambda: run_on_ui_thread(post_reload)).start()

    def _load_jvm_plugin(self):
        self.jvm_plugin = JvmPluginBridge(self)
        self.jvm_plugin.load()

        if self.jvm_plugin.klass is None:
            return

        try:
            build_date = self.jvm_plugin.klass.getDeclaredMethod(
                String("getBuildDate")
            ).invoke(None)  # ty:ignore[invalid-argument-type]
            self.log(f"Loading JVM plugin {build_date}")
        except Exception as e:
            self.log(f"Failed to infer JVM plugin version: {e}")
            return

        try:
            ref = self

            class Logger(dynamic_proxy(ValueCallback)):
                def onReceiveValue(self, var1):
                    ref.log(var1)

            class StreakResolver(dynamic_proxy(Function)):
                def apply(self, t: Long):
                    if t <= 0:
                        return None

                    record = ref.users_db.get_user(t)

                    if record is not None:
                        length = record.get_length()
                        if int(length) < 3:
                            return None

                        streak_level = StreakLevels.pick_by_length(
                            length, record.is_freezed()
                        )

                        return jarray(Object)(
                            [
                                Integer(length),
                                Long(streak_level.document_id),
                                streak_level.accent_color,
                                Boolean(StreakLevels.is_jubilee(length)),
                            ],
                        )

                    return None

            class TranslationResolver(dynamic_proxy(Function)):
                def apply(self, t: String):
                    if t is None:
                        return ""
                    return ref._t(str(t))

            class ReviveResolver(dynamic_proxy(Function)):
                def apply(self, t: Long):
                    if t is None:
                        return Boolean(False)

                    return Boolean(ref._revive_streak_from_dialog_id(int(t)))

            self.jvm_plugin.klass.getDeclaredMethod(
                String("inject"),
                ValueCallback.getClass(),  # ty:ignore[unresolved-attribute]
                Function.getClass(),  # ty:ignore[unresolved-attribute]
                Function.getClass(),  # ty:ignore[unresolved-attribute]
                Function.getClass(),  # ty:ignore[unresolved-attribute]
            ).invoke(
                cast("Object", None),
                cast("Object", Logger()),
                cast("Object", StreakResolver()),
                cast("Object", TranslationResolver()),
                cast("Object", ReviveResolver()),
            )
            self.log("JVM plugin injected successfully")
        except Exception as e:
            self.log(f"Failed to inject JVM plugin: {e}")
            return

    def on_plugin_load(self):
        self._force_check_lock = threading.Lock()
        self._force_check_running = False
        self._chat_force_check_menu_item_id = None
        self._chat_go_to_streak_start_menu_item_id = None
        self._chat_upgrade_service_messages_menu_item_id = None
        self._chat_restore_streak_menu_item_id = None
        self._chat_upgrade_service_state_cache = {}
        self._dialog_account_map: dict[int, int] = {}
        self._chat_debug_create_streak_menu_item_id = None
        self._chat_debug_kill_streak_menu_item_id = None
        self._chat_debug_upgrade_streak_menu_item_id = None
        self._streak_popup_last: dict[str, int] = {}
        self._streak_popup_queue_lock = threading.Lock()
        self._pending_streak_popups: dict[int, list[dict[str, Any]]] = {}
        self._streak_popup_dispatch_stop = threading.Event()
        self._streak_dead_state: dict[int, bool] = {}
        self._streak_dead_monitor_stop = threading.Event()
        self._update_check_stop = threading.Event()
        self._update_check_lock = threading.Lock()
        self._update_check_inflight = False
        self._upgrade_service_lock = threading.Lock()
        self._sent_upgrade_service_messages: set[str] = set()

        self._load_jvm_plugin()

        self.add_on_send_message_hook()
        self.add_hook("TL_updateNewMessage")
        self.add_hook("TL_updateNewChannelMessage")
        self.add_hook("TL_updateEditMessage")
        self.add_hook("TL_updateEditChannelMessage")
        self.add_hook("TL_updateShortMessage")
        self.add_hook("TL_updateShortChatMessage")
        self.add_hook("TL_updateShort")
        self.add_hook("TL_updates")
        self.add_hook("TL_updatesCombined")

        try:
            self.users_db = UsersDatabase(jvm_plugin=self.jvm_plugin, logger=self.log)
            self.streaks = StreaksController(users_db=self.users_db, logger=self.log)
            self._self_user_ids = {}
            self._consumed_tagged_message_keys: set[str] = set()

            checked, updated, removed = self.streaks.reconcile_on_plugin_load()
            if checked > 0:
                self.log(
                    f"Streak startup check done: checked={checked}, updated={updated}, removed={removed}"
                )

            self.hook_message_object_constructors()
            self.hook_user_emoji_status_assign(True)
            self._rerender_dialog_cells()
            self._register_chat_action_menu_items()
            self._sync_dead_states_snapshot()
            self._start_dead_state_monitor()
            self._start_streak_popup_dispatch_monitor()
            self._start_update_check()
        except Exception as e:
            self.log(f"Exception: {e}")

    def on_plugin_unload(self):
        try:
            self._streak_dead_monitor_stop.set()
        except Exception:
            pass

        try:
            self._streak_popup_dispatch_stop.set()
        except Exception:
            pass

        try:
            self._update_check_stop.set()
        except Exception:
            pass

        self._unregister_chat_action_menu_items()

        if self.jvm_plugin.klass is None:
            return

        try:
            self.jvm_plugin.klass.getDeclaredMethod(String("eject")).invoke(None)  # ty:ignore[invalid-argument-type]
            self.log("JVM plugin ejected successfully")
        except Exception as e:
            self.log(f"Failed to eject JVM plugin: {e}")

        self.jvm_plugin.klass = None

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        try:
            if self._is_upgrade_service_send_params(params):
                return HookResult()

            peer_id = self._extract_private_peer_id_from_send_params(params)

            if peer_id is None:
                return HookResult()

            self_user_id = self._get_self_user_id(account)

            if self_user_id > 0 and peer_id == self_user_id:
                return HookResult()

            self._notify_for_dialog_event(account, peer_id, SenderType.SELF)
        except Exception as e:
            self.log(f"send hook error: {e}")

        return HookResult()

    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult:
        try:
            self._log_service_update_trace(
                "on_update_hook", account, update_name, update
            )
            self._consume_update(account, update_name, update)
        except Exception as e:
            self.log(f"update hook error ({update_name}): {e}")

        return HookResult()

    def on_updates_hook(
        self, container_name: str, account: int, updates: Any
    ) -> HookResult:
        try:
            self._log_service_update_trace(
                "on_updates_hook", account, container_name, updates
            )
            self._consume_update(account, container_name, updates)
        except Exception as e:
            self.log(f"updates hook error ({container_name}): {e}")

        return HookResult()
