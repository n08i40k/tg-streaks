package ru.n08i40k.streaks.constants

object TranslationKey {
    object Sheet {
        object StreakInfo {
            const val DIALOG_TITLE = "sheet.streak_info.dialog_title"
            const val HEADER_TITLE = "sheet.streak_info.header.title"
            const val HEADER_DESCRIPTION = "sheet.streak_info.header.description"
            const val FEATURE_HOW_TITLE = "sheet.streak_info.feature.how.title"
            const val FEATURE_HOW_DESCRIPTION = "sheet.streak_info.feature.how.description"
            const val FEATURE_LEVELS_TITLE = "sheet.streak_info.feature.levels.title"
            const val FEATURE_LEVELS_DESCRIPTION =
                "sheet.streak_info.feature.levels.description"
            const val FEATURE_KEEP_TITLE = "sheet.streak_info.feature.keep.title"
            const val FEATURE_KEEP_DESCRIPTION = "sheet.streak_info.feature.keep.description"
            const val FEATURE_FIX_DURATION_TITLE =
                "sheet.streak_info.feature.fix_duration.title"
            const val FEATURE_FIX_DURATION_DESCRIPTION =
                "sheet.streak_info.feature.fix_duration.description"
        }

        object Pet {
            const val STREAK_DAYS = "sheet.pet.streak_days"
            const val POINTS_TO_NEXT_STAGE = "sheet.pet.points_to_next_stage"
            const val MAX_LEVEL = "sheet.pet.max_level"
            const val LOCKED = "sheet.pet.locked"
            const val LOCKED_DESCRIPTION = "sheet.pet.locked.description"
            const val TASKS_TITLE = "sheet.pet.tasks.title"
            const val BADGES_TITLE = "sheet.pet.badges.title"
            const val TASK_EXCHANGE_ONE_MESSAGE = "sheet.pet.tasks.exchange_one_message"
            const val TASK_SEND_FOUR_MESSAGES_EACH = "sheet.pet.tasks.send_four_messages_each"
            const val TASK_SEND_TEN_MESSAGES_EACH = "sheet.pet.tasks.send_ten_messages_each"
            const val PROGRESS_YOU = "sheet.pet.progress.you"
            const val PROGRESS_PARTNER = "sheet.pet.progress.partner"
            const val RENAME_TITLE = "sheet.pet.rename.title"
            const val RENAME_PLACEHOLDER = "sheet.pet.rename.placeholder"
            const val RENAME_SAVE = "sheet.pet.rename.save"
            const val RENAME_CANCEL = "sheet.pet.rename.cancel"
        }
    }

    object Rebuild {
        object Streak {
            const val PROGRESS_CHAT = "rebuild.streak.progress.chat"
            const val PROGRESS_ALL_CHATS = "rebuild.streak.progress.all_chats"
            const val SUMMARY_ALL_CHATS = "rebuild.streak.summary.all_chats"
            const val SUMMARY_CHAT = "rebuild.streak.summary.chat"
            const val RETRY_DELAY = "rebuild.streak.retry_delay"
        }
    }

    object Status {
        object Error {
            const val CHAT_DETECT_CURRENT_FAILED = "status.error.chat.detect_current_failed"
            const val CHAT_OPEN_CONTEXT_FAILED = "status.error.chat.open_context_failed"
            const val STREAK_JUMP_TO_START_FAILED = "status.error.streak.jump_to_start_failed"
            const val BACKUP_EXPORT_FAILED = "status.error.backup.export_failed"
            const val BACKUP_NOT_FOUND = "status.error.backup.not_found"
            const val BACKUP_APPLY_FAILED = "status.error.backup.apply_failed"
            const val DATABASE_DELETE_FAILED = "status.error.database.delete_failed"
            const val UPDATE_OPEN_LINK_FAILED = "status.error.update.open_link_failed"
            const val REBUILD_FAILED_CHECK_LOGS = "status.error.rebuild.failed_check_logs"
        }

        object Success {
            const val BACKUP_EXPORTED = "status.success.backup.exported"
            const val BACKUP_IMPORTED = "status.success.backup.imported"
            const val DATABASE_RESET_STARTED = "status.success.database.reset_started"
            const val STREAK_JUMP_TO_START_COMPLETED =
                "status.success.streak.jump_to_start_completed"
            const val STREAK_RESTORED = "status.success.streak.restored"
            const val CHAT_LEVEL_MESSAGES_ENABLED = "status.success.chat.level_messages_enabled"
            const val CHAT_LEVEL_MESSAGES_DISABLED =
                "status.success.chat.level_messages_disabled"
            const val PET_BUTTON_ENABLED = "status.success.pet_button.enabled"
            const val PET_BUTTON_DISABLED = "status.success.pet_button.disabled"
            const val PET_CREATED = "status.success.pet.created"
            const val DEBUG_STREAK_SET_TO_3_DAYS =
                "status.success.debug.streak_set_to_3_days"
            const val DEBUG_STREAK_MARKED_DEAD =
                "status.success.debug.streak_marked_dead"
            const val DEBUG_STREAK_UPGRADED = "status.success.debug.streak_upgraded"
            const val DEBUG_STREAK_FROZEN = "status.success.debug.streak_frozen"
            const val DEBUG_STREAK_DELETED = "status.success.debug.streak_deleted"
            const val DEBUG_PET_DELETED = "status.success.debug.pet_deleted"
        }

        object Info {
            const val CHAT_PRIVATE_USERS_ONLY = "status.info.chat.private_users_only"
            const val CHAT_BOTS_NOT_SUPPORTED = "status.info.chat.bots_not_supported"
            const val CHAT_DELETED_USERS_NOT_SUPPORTED =
                "status.info.chat.deleted_users_not_supported"
            const val PET_NOT_CREATED_FOR_CHAT = "status.info.pet.not_created_for_chat"
            const val PET_ALREADY_EXISTS_FOR_CHAT =
                "status.info.pet.already_exists_for_chat"
            const val STREAK_NOT_ENDED_YET = "status.info.streak.not_ended_yet"
            const val STREAK_RESTORE_UNAVAILABLE =
                "status.info.streak.restore_unavailable"
            const val STREAK_SEARCHING_START_MESSAGE =
                "status.info.streak.searching_start_message"
            const val STREAK_START_MESSAGE_NOT_FOUND =
                "status.info.streak.start_message_not_found"
            const val STREAK_NOT_FOUND_FOR_CHAT = "status.info.streak.not_found_for_chat"
            const val REBUILD_ALREADY_RUNNING = "status.info.rebuild.already_running"
            const val REBUILD_STARTED_ALL_CHATS =
                "status.info.rebuild.started_all_chats"
            const val DEBUG_PRIVATE_USERS_ONLY =
                "status.info.debug.private_users_only"
            const val DEBUG_STREAK_ALREADY_MAX =
                "status.info.debug.streak_already_max"
        }
    }

    object Dialog {
        object CreatePet {
            const val TITLE = "dialog.create_pet.title"
            const val MESSAGE = "dialog.create_pet.message"
            const val CONFIRM = "dialog.create_pet.confirm"
            const val CANCEL = "dialog.create_pet.cancel"
        }

        object BackupRestore {
            const val TITLE = "dialog.backup_restore.title"
        }
    }

    object Service {
        object Streak {
            const val STARTED_TEXT = "service.streak.started.text"
            const val LEVEL_UP_TEXT = "service.streak.level_up.text"
            const val ENDED_TITLE = "service.streak.ended.title"
            const val ENDED_SUBTITLE = "service.streak.ended.subtitle"
            const val ENDED_HINT = "service.streak.ended.hint"
            const val ENDED_ACTION = "service.streak.ended.action"
            const val RESTORED_SELF = "service.streak.restored.self"
            const val RESTORED_PEER = "service.streak.restored.peer"
        }

        object Pet {
            object Invite {
                const val TITLE = "service.pet.invite.title"
                const val DESCRIPTION = "service.pet.invite.description"
                const val HINT = "service.pet.invite.hint"
                const val ACTION = "service.pet.invite.action"
                const val SENT_SELF = "service.pet.invite.sent.self"
                const val ACCEPTED_PEER = "service.pet.invite.accepted.peer"
                const val ACCEPTED_SELF = "service.pet.invite.accepted.self"
            }

            object Rename {
                const val SELF = "service.pet.rename.self"
                const val PEER = "service.pet.rename.peer"
            }
        }
    }
}
