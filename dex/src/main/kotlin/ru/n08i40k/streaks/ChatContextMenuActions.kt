package ru.n08i40k.streaks

import kotlinx.coroutines.DelicateCoroutinesApi
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.constants.ChatContextMenuButton
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger

class ChatContextMenuActions(private val plugin: Plugin) {
    @OptIn(DelicateCoroutinesApi::class)
    fun register() = with(plugin) {
        fun add(key: String, callback: (Long) -> Unit) {
            chatContextMenuCallbackRegistry.register(key) {
                Logger.tryOrFatal("handle context menu entry touch") {
                    callback(it)
                }
            }
        }

        fun validateDebugPeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (!isPeerValid(peerUser)) {
                BulletinHelper.show(Strings.status_info_debug_private_users_only())
                return null
            }

            return peerUser
        }

        fun validatePrivatePeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (!isPeerValid(peerUser)) {
                BulletinHelper.show(Strings.status_info_chat_private_users_only())
                return null
            }

            return peerUser
        }

        add(ChatContextMenuButton.REBUILD) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            enqueueRebuildForPeer(accountId, peerUserId)

            Logger.info("[Context Menu] Rebuild clicked on $peerUserId")
        }

        add(ChatContextMenuButton.REBUILD_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            if (streakPetsController.isRebuildRunning()) {
                BulletinHelper.show(Strings.status_info_rebuild_already_running())
                return@add
            }

            AccountTaskExecutor.enqueue(
                accountId,
                "rebuild streak-pet for $accountId:$peerUserId"
            ) {
                val streakPet = streakPetsController.get(accountId, peerUserId)

                if (streakPet == null) {
                    BulletinHelper.show(Strings.status_info_pet_not_created_for_chat())
                    return@enqueue
                }

                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@enqueue
                }

                streakPetsController.rebuild(accountId, peerUser)
            }

            Logger.info("[Context Menu] Rebuild pet clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_PET_FAB) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            val petFabEnabled = petUiManager.toggleFabEnabled()

            if (petFabEnabled) {
                BulletinHelper.show(
                    Strings.status_success_pet_button_enabled(),
                    "msg_reactions"
                )
            } else {
                BulletinHelper.show(
                    Strings.status_success_pet_button_disabled(),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Toggle pet fab clicked on $peerUserId; enabled=$petFabEnabled")
        }

        add(ChatContextMenuButton.CREATE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "try to create streak-pet for $accountId:$peerUserId"
            ) {
                if (streakPetsController.get(accountId, peerUserId) != null) {
                    BulletinHelper.show(Strings.status_info_pet_already_exists_for_chat())
                    return@enqueue
                }

                AndroidUtilities.runOnUIThread {
                    val fragment = LaunchActivity.getSafeLastFragment()
                    if (fragment == null) {
                        BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                        return@runOnUIThread
                    }

                    fragment.showDialog(
                        AlertDialog.Builder(fragment.context)
                            .setTitle(Strings.dialog_create_pet_title())
                            .setMessage(Strings.dialog_create_pet_message())
                            .setPositiveButton(
                                Strings.dialog_create_pet_confirm()
                            ) { _, _ ->
                                serviceMessagesController.setEnabled(accountId, peerUserId, true)
                                serviceMessagesController.sendPetInvite(accountId, peerUserId)
                            }
                            .setNegativeButton(
                                Strings.dialog_create_pet_cancel()
                            ) { _, _ ->
                                AccountTaskExecutor.enqueue(
                                    accountId,
                                    "create streak-pet for $accountId:$peerUserId"
                                ) {
                                    if (!streakPetsController.create(accountId, peerUserId)) {
                                        BulletinHelper.show(Strings.status_info_pet_already_exists_for_chat())
                                        return@enqueue
                                    }

                                    BulletinHelper.show( Strings.status_success_pet_created(), "msg_reactions")
                                }
                            }
                            .create()
                    )
                }
            }

            Logger.info("[Context Menu] Create pet clicked on $peerUserId")
        }

        add(ChatContextMenuButton.GO_TO_STREAK_START) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            if (peerUserId <= 0L || peerUserId == ownerUserId) {
                BulletinHelper.show(Strings.status_info_chat_private_users_only())
                return@add
            }

            val chatActivity = (LaunchActivity.getSafeLastFragment() as? ChatActivity)
                ?.takeIf { it.dialogId == peerUserId }

            if (chatActivity == null) {
                BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                Logger.info("[Context Menu] Go-to-streak-start failed: no chat context for $peerUserId")
                return@add
            }

            BulletinHelper.show(Strings.status_info_streak_searching_start_message())

            AccountTaskExecutor.enqueue(
                accountId,
                "go to streak start for $accountId:$peerUserId"
            ) {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@enqueue
                }

                val jumpTs = streak.createdAt.toEpochSecondSystem().toInt()
                val messageId = streaksController.findStartMessageId(accountId, peerUserId)

                AndroidUtilities.runOnUIThread {
                    if (messageId != null && messageId > 0) {
                        chatActivity.scrollToMessageId(messageId, 0, true, 0, true, 0)
                        BulletinHelper.show(Strings.status_success_streak_jump_to_start_completed())
                    } else {
                        chatActivity.jumpToDate(jumpTs)
                        BulletinHelper.show(Strings.status_info_streak_start_message_not_found())
                    }
                }
            }

            Logger.info("[Context Menu] Go-to-streak-start clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_SERVICE_MESSAGES) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            val enabled = serviceMessagesController.toggle(accountId, peerUserId)

            BulletinHelper.show(
                if (enabled)
                    Strings.status_success_chat_level_messages_enabled()
                else
                    Strings.status_success_chat_level_messages_disabled(),
                "msg_reactions"
            )

            Logger.info("[Context Menu] Toggle service messages clicked on $peerUserId; enabled=$enabled")
        }

        add(ChatContextMenuButton.REVIVE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "revive streak for $accountId:$peerUserId"
            ) {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@enqueue
                }

                if (!streak.dead) {
                    BulletinHelper.show(Strings.status_info_streak_not_ended_yet())
                    return@enqueue
                }

                if (!streak.canRevive) {
                    BulletinHelper.show(Strings.status_info_streak_restore_unavailable())
                    return@enqueue
                }

                if (!streaksController.revive(accountId, peerUserId)) {
                    BulletinHelper.show(Strings.status_info_streak_restore_unavailable())
                    return@enqueue
                }

                BulletinHelper.show(
                    Strings.status_success_streak_restored(),
                    "msg_reactions"
                )
            }
        }

        add(ChatContextMenuButton.REVIVE_EXACT) { _ ->
            val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity
                ?: run {
                    BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                    return@add
                }

            AndroidUtilities.runOnUIThread {
                chatActivity.presentFragment(
                    FixupCalendarActivity.create(
                        chatActivity.dialogId,
                        chatActivity
                    )
                )
            }
        }

        add(ChatContextMenuButton.DEBUG_CREATE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "create debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugSetThreeDayStreak(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_set_to_3_days(),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-create clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_UPGRADE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "upgrade debug streak for $accountId:$peerUserId"
            ) {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@enqueue
                }

                val nextLevel = streakLevelRegistry
                    .levels()
                    .firstOrNull { level -> level.length > streak.level.length }

                if (nextLevel == null) {
                    BulletinHelper.show(Strings.status_info_debug_streak_already_max())
                    return@enqueue
                }

                val newLength = streaksController.debugUpgradeStreak(accountId, peerUserId)
                    ?: return@enqueue

                BulletinHelper.show(
                    Strings.status_success_debug_streak_upgraded(newLength),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-upgrade clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_FREEZE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "freeze debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugFreezeStreak(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_frozen(),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-freeze clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_KILL) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "kill debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugMarkDead(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_marked_dead(),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-kill clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "delete debug streak for $accountId:$peerUserId"
            ) {
                if (!streaksController.delete(accountId, peerUserId)) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@enqueue
                }

                BulletinHelper.show(
                    Strings.status_success_debug_streak_deleted(),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-delete clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            AccountTaskExecutor.enqueue(
                accountId,
                "delete debug streak for $accountId:$peerUserId"
            ) {
                if (!streakPetsController.delete(accountId, peerUserId)) {
                    BulletinHelper.show(Strings.status_info_pet_not_created_for_chat())
                    return@enqueue
                }

                BulletinHelper.show(Strings.status_success_debug_pet_deleted(), "msg_reactions")
            }

            Logger.info("[Context Menu] Debug-delete-pet clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_CRASH) { _ ->
            throw RuntimeException("Crash button was pressed")
        }

        chatContextMenuCallbackRegistry.freeze()
    }
}
