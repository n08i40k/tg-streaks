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
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.Translator

class ChatContextMenuActions(private val plugin: Plugin) {
    @OptIn(DelicateCoroutinesApi::class)
    fun register() = with(plugin) {
        fun add(key: String, callback: (Long) -> Unit) {
            chatContextMenuCallbackRegistry.register(key) {
                try {
                    callback(it)
                } catch (e: Throwable) {
                    Logger.fatal("An error occurred while handling context menu entry touch", e)
                }
            }
        }

        fun validateDebugPeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (!isPeerValid(peerUser)) {
                bulletinHelper.showTranslated(TranslationKey.Status.Info.DEBUG_PRIVATE_USERS_ONLY)
                return null
            }

            return peerUser
        }

        fun validatePrivatePeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (!isPeerValid(peerUser)) {
                bulletinHelper.showTranslated(TranslationKey.Status.Info.CHAT_PRIVATE_USERS_ONLY)
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
                bulletinHelper.showTranslated(TranslationKey.Status.Info.REBUILD_ALREADY_RUNNING)
                return@add
            }

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "rebuild streak-pet for $accountId:$peerUserId"
            ) {
                val streakPet = streakPetsController.get(accountId, peerUserId)

                if (streakPet == null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.PET_NOT_CREATED_FOR_CHAT)
                    return@enqueue
                }

                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_FOUND_FOR_CHAT)
                    return@enqueue
                }

                val peerName = peerUser.label

                streakPetsController.rebuild(accountId, peerUser) { progress ->
                    rebuildNotificationHelper.updateSinglePetProgress(peerName, progress.daysChecked)
                }

                rebuildNotificationHelper.completeSinglePet(peerName)
            }

            Logger.info("[Context Menu] Rebuild pet clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_PET_FAB) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            val petFabEnabled = petUiManager.toggleFabEnabled()

            if (petFabEnabled) {
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.PET_BUTTON_ENABLED,
                    "msg_reactions"
                )
            } else {
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.PET_BUTTON_DISABLED,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Toggle pet fab clicked on $peerUserId; enabled=$petFabEnabled")
        }

        add(ChatContextMenuButton.CREATE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "try to create streak-pet for $accountId:$peerUserId"
            ) {
                if (streakPetsController.get(accountId, peerUserId) != null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.PET_ALREADY_EXISTS_FOR_CHAT)
                    return@enqueue
                }

                AndroidUtilities.runOnUIThread {
                    val fragment = LaunchActivity.getSafeLastFragment()
                    if (fragment == null) {
                        bulletinHelper.showTranslated(TranslationKey.Status.Error.CHAT_OPEN_CONTEXT_FAILED)
                        return@runOnUIThread
                    }

                    fragment.showDialog(
                        AlertDialog.Builder(fragment.context)
                            .setTitle(Translator.translate(TranslationKey.Dialog.CreatePet.TITLE))
                            .setMessage(Translator.translate(TranslationKey.Dialog.CreatePet.MESSAGE))
                            .setPositiveButton(
                                Translator.translate(TranslationKey.Dialog.CreatePet.CONFIRM)
                            ) { _, _ ->
                                streaksController.setServiceMessagesEnabled(
                                    accountId,
                                    peerUserId,
                                    true
                                )
                                serviceMessagesController.sendPetInvite(accountId, peerUserId)
                            }
                            .setNegativeButton(
                                Translator.translate(TranslationKey.Dialog.CreatePet.CANCEL)
                            ) { _, _ ->
                                accountTaskRunnerRegistry.enqueue(
                                    accountId,
                                    "create streak-pet for $accountId:$peerUserId"
                                ) {
                                    when (streakPetsController.create(accountId, peerUserId)) {
                                        is StreakPetsController.CreateResult.Created -> {
                                            petUiManager.refreshFabForOpenChat()
                                            bulletinHelper.showTranslated(
                                                TranslationKey.Status.Success.PET_CREATED,
                                                "msg_reactions"
                                            )
                                        }

                                        is StreakPetsController.CreateResult.AlreadyExists -> {
                                            bulletinHelper.showTranslated(
                                                TranslationKey.Status.Info.PET_ALREADY_EXISTS_FOR_CHAT
                                            )
                                        }
                                    }
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
                bulletinHelper.showTranslated(TranslationKey.Status.Info.CHAT_PRIVATE_USERS_ONLY)
                return@add
            }

            val chatActivity = (LaunchActivity.getSafeLastFragment() as? ChatActivity)
                ?.takeIf { it.dialogId == peerUserId }

            if (chatActivity == null) {
                bulletinHelper.showTranslated(TranslationKey.Status.Error.CHAT_OPEN_CONTEXT_FAILED)
                Logger.info("[Context Menu] Go-to-streak-start failed: no chat context for $peerUserId")
                return@add
            }

            bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_SEARCHING_START_MESSAGE)

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "go to streak start for $accountId:$peerUserId"
            ) {
                try {
                    val streak = streaksController.get(accountId, peerUserId)

                    if (streak == null) {
                        bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_FOUND_FOR_CHAT)
                        return@enqueue
                    }

                    val jumpTs = streak.createdAt.toEpochSecondSystem().toInt()
                    val messageId = streaksController.findStartMessageId(accountId, peerUserId)

                    AndroidUtilities.runOnUIThread {
                        try {
                            if (messageId != null && messageId > 0) {
                                try {
                                    chatActivity.scrollToMessageId(messageId, 0, true, 0, true, 0)
                                    bulletinHelper.showTranslated(
                                        TranslationKey.Status.Success.STREAK_JUMP_TO_START_COMPLETED
                                    )
                                    return@runOnUIThread
                                } catch (e: Throwable) {
                                    Logger.info(
                                        "[Context Menu] Go-to-streak-start scroll failed for $peerUserId: ${e.message}"
                                    )
                                }
                            }

                            chatActivity.jumpToDate(jumpTs)
                            bulletinHelper.showTranslated(
                                TranslationKey.Status.Info.STREAK_START_MESSAGE_NOT_FOUND
                            )
                        } catch (e: Throwable) {
                            Logger.fatal(
                                "Go-to-streak-start failed for peer $peerUserId",
                                e
                            )
                            bulletinHelper.showTranslated(
                                TranslationKey.Status.Error.STREAK_JUMP_TO_START_FAILED
                            )
                        }
                    }
                } catch (e: Throwable) {
                    Logger.fatal("Go-to-streak-start lookup failed for peer $peerUserId", e)
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.STREAK_JUMP_TO_START_FAILED)
                }
            }

            Logger.info("[Context Menu] Go-to-streak-start clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_SERVICE_MESSAGES) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            val enabled = streaksController.toggleServiceMessages(accountId, peerUserId)

            bulletinHelper.showTranslated(
                if (enabled) {
                    TranslationKey.Status.Success.CHAT_LEVEL_MESSAGES_ENABLED
                } else {
                    TranslationKey.Status.Success.CHAT_LEVEL_MESSAGES_DISABLED
                },
                "msg_reactions"
            )

            Logger.info("[Context Menu] Toggle service messages clicked on $peerUserId; enabled=$enabled")
        }

        add(ChatContextMenuButton.REVIVE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "revive streak for $accountId:$peerUserId"
            ) {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_FOUND_FOR_CHAT)
                    return@enqueue
                }

                if (!streak.dead) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_ENDED_YET)
                    return@enqueue
                }

                if (!streak.canRevive) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_RESTORE_UNAVAILABLE)
                    return@enqueue
                }

                if (!streaksController.reviveNow(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_RESTORE_UNAVAILABLE)
                    return@enqueue
                }

                if (streaksController.patchUser(accountId, peerUser))
                    MessagesController.getInstance(accountId).putUser(peerUser, false, true)

                streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.STREAK_RESTORED,
                    "msg_reactions"
                )
            }
        }

        add(ChatContextMenuButton.REVIVE_EXACT) { _ ->
            val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity
                ?: run {
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.CHAT_OPEN_CONTEXT_FAILED)
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

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "create debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugSetThreeDayStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_STREAK_SET_TO_3_DAYS,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-create clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_UPGRADE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "upgrade debug streak for $accountId:$peerUserId"
            ) {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_FOUND_FOR_CHAT)
                    return@enqueue
                }

                val nextLevel = streakLevelRegistry
                    .levels()
                    .firstOrNull { level -> level.length > streak.level.length }

                if (nextLevel == null) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.DEBUG_STREAK_ALREADY_MAX)
                    return@enqueue
                }

                val newLength = streaksController.debugUpgradeStreak(accountId, peerUserId)
                    ?: return@enqueue

                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_STREAK_UPGRADED,
                    mapOf("days" to newLength.toString()),
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-upgrade clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_FREEZE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "freeze debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugFreezeStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_STREAK_FROZEN,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-freeze clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_KILL) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "kill debug streak for $accountId:$peerUserId"
            ) {
                streaksController.debugMarkDead(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_STREAK_MARKED_DEAD,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-kill clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "delete debug streak for $accountId:$peerUserId"
            ) {
                if (!streaksController.debugDeleteStreak(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.STREAK_NOT_FOUND_FOR_CHAT)
                    return@enqueue
                }

                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_STREAK_DELETED,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-delete clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount

            val peerUser = validateDebugPeer(accountId, peerUserId)
                ?: return@add

            accountTaskRunnerRegistry.enqueue(
                accountId,
                "delete debug streak for $accountId:$peerUserId"
            ) {
                if (!streakPetsController.delete(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.Status.Info.PET_NOT_CREATED_FOR_CHAT)
                    return@enqueue
                }

                AndroidUtilities.runOnUIThread { petUiManager.dismissAll() }
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.DEBUG_PET_DELETED,
                    "msg_reactions"
                )
            }

            Logger.info("[Context Menu] Debug-delete-pet clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_CRASH) { _ ->
            throw RuntimeException("Crash button was pressed")
        }

        chatContextMenuCallbackRegistry.freeze()
    }
}
