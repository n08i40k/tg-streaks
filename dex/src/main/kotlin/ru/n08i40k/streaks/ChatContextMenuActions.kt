package ru.n08i40k.streaks

import android.net.Uri
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.constants.ChatContextMenuButton
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.ui.StreakControlFragment
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.getFieldValue
import kotlin.time.Clock

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

        add(ChatContextMenuButton.CONTROL_MENU) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            validatePrivatePeer(accountId, peerUserId)
                ?: return@add

            val viewModel = object : StreakControlFragment.ViewModel {
                private val stateFlow = MutableStateFlow(StreakControlFragment.ViewState())

                init {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "load control state for $accountId:$peerUserId"
                    ) {
                        refreshState()
                    }
                }

                private suspend fun refreshState() {
                    stateFlow.value = StreakControlFragment.ViewState(
                        hasPet =
                            streakPetsController.exists(accountId, peerUserId),

                        timeZone =
                            timeZonesController.get(ownerUserId, peerUserId),

                        peerHasPluginInstalled =
                            pluginRelationController.hasPlugin(ownerUserId, peerUserId),

                        petFabEnabled =
                            streakPetsController.isFabEnabled(accountId, peerUserId) ?: true,

                        canRestoreStreak =
                            streaksController.get(accountId, peerUserId)?.ended == true,

                        serviceMessageCategories =
                            ServiceMessageCategory.all.associateWith { category ->
                                serviceMessageCategoriesController.isEnabled(
                                    ownerUserId,
                                    peerUserId,
                                    category
                                )
                            },
                    )
                }

                override fun state(): Flow<StreakControlFragment.ViewState> = stateFlow

                override fun setTimeZone(value: TimeZone) {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "set time zone for $accountId:$peerUserId"
                    ) {
                        timeZonesController.set(ownerUserId, peerUserId, value)

                        val peerUser =
                            MessagesController.getInstance(accountId).getUser(peerUserId)

                        if (peerUser != null && isPeerValid(peerUser)) {
                            streaksController.rebuild(accountId, peerUser)

                            if (streaksController.exists(accountId, peerUserId) &&
                                streakPetsController.exists(accountId, peerUserId)
                            ) {
                                streakPetsController.rebuild(accountId, peerUser)
                            }
                        }

                        refreshState()
                    }
                }

                override fun setPeerHasPluginInstalled(value: Boolean) {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "set peer has installed for $accountId:$peerUserId"
                    ) {
                        pluginRelationController.setHasPlugin(ownerUserId, peerUserId, value)

                        refreshState()
                    }
                }

                override fun offerSync() {
                    AccountTaskExecutor.enqueue(accountId, "send sync") {
                        val swapped =
                            databaseBackupManager.exportSwappedNow(ownerUserId, peerUserId)

                        serviceMessagesController.sendSyncOffer(
                            accountId,
                            peerUserId,
                            Uri.fromFile(swapped)
                        )
                    }
                }

                override fun setServiceMessagesCategoryEnabled(
                    categoryName: String,
                    value: Boolean
                ) {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "set service message category $categoryName for $accountId:$peerUserId"
                    ) {
                        serviceMessageCategoriesController.setEnabled(
                            ownerUserId,
                            peerUserId,
                            categoryName,
                            value
                        )
                        refreshState()
                    }
                }

                override fun setPetFabEnabled(value: Boolean) {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "set pet fab enabled for $accountId:$peerUserId"
                    ) {
                        streakPetsController.setFabEnabled(accountId, peerUserId, value)
                        refreshState()
                    }
                }

                override fun rebuildBoth() {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "rebuild streak+pet for $accountId:$peerUserId"
                    ) {
                        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)
                            ?: return@enqueue

                        streaksController.rebuild(accountId, peerUser)

                        if (streakPetsController.exists(accountId, peerUserId))
                            streakPetsController.rebuild(accountId, peerUser)

                        refreshState()
                    }
                }

                override fun rebuildPet() {
                    if (streakPetsController.isRebuildRunning()) {
                        BulletinHelper.show(Strings.status_info_rebuild_already_running())
                        return
                    }

                    AccountTaskExecutor.enqueue(
                        accountId,
                        "rebuild pet for $accountId:$peerUserId"
                    ) {
                        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)
                            ?: return@enqueue

                        streakPetsController.rebuild(accountId, peerUser)
                        refreshState()
                    }
                }

                override fun restoreStreak() {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "revive streak for $accountId:$peerUserId"
                    ) {
                        val streak = streaksController.get(accountId, peerUserId)

                        if (streak == null) {
                            BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                            return@enqueue
                        }

                        if (!streak.ended) {
                            BulletinHelper.show(Strings.status_info_streak_not_ended_yet())
                            return@enqueue
                        }

                        if (!streak.canRestore) {
                            BulletinHelper.show(Strings.status_info_streak_restore_unavailable())
                            return@enqueue
                        }

                        if (!streaksController.restore(
                                accountId,
                                peerUserId,
                                Clock.System.now()
                            )
                        ) {
                            BulletinHelper.show(Strings.status_info_streak_restore_unavailable())
                            return@enqueue
                        }

                        BulletinHelper.show(
                            Strings.status_success_streak_restored(),
                            "msg_reactions"
                        )

                        refreshState()
                    }
                }

                override fun createPet() {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "create streak-pet for $accountId:$peerUserId"
                    ) {
                        if (streakPetsController.exists(accountId, peerUserId))
                            return@enqueue

                        if (pluginRelationController.hasPlugin(ownerUserId, peerUserId)) {
                            serviceMessagesController.sendPetInvite(accountId, peerUserId)

                            BulletinHelper.show(Strings.status_success_pet_invite_sent())

                            return@enqueue
                        }

                        if (!streakPetsController.create(accountId, peerUserId)) {
                            BulletinHelper.show(Strings.status_info_pet_already_exists_for_chat())
                            return@enqueue
                        }

                        BulletinHelper.show(Strings.status_success_pet_created(), "msg_reactions")

                        refreshState()
                    }
                }

                override fun goToStreakStart() {
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

                        val jumpTs = streak.createdAt.epochSeconds.toInt()

                        // wait for dialog activity appear
                        delay(2000)

                        AndroidUtilities.runOnUIThread {
                            val chatActivity =
                                (LaunchActivity.getSafeLastFragment() as? ChatActivity)
                                    ?.takeIf { it.dialogId == peerUserId }

                            if (chatActivity == null) {
                                BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                                return@runOnUIThread
                            }

                            chatActivity.jumpToDate(jumpTs)
                            BulletinHelper.show(Strings.status_success_streak_jump_to_start_completed())
                        }
                    }
                }

                override fun deleteBoth() {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "delete streak with pet for $accountId:$peerUserId"
                    ) {
                        streaksController.delete(accountId, peerUserId)
                        streakPetsController.delete(accountId, peerUserId)

                        refreshState()
                    }
                }

                override fun deletePet() {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "delete pet for $accountId:$peerUserId"
                    ) {
                        streakPetsController.delete(accountId, peerUserId)

                        refreshState()
                    }
                }
            }

            val fragment = StreakControlFragment(viewModel)

            AndroidUtilities.runOnUIThread {
                getFieldValue<ActionBarLayout>(LaunchActivity.instance, "actionBarLayout")
                    ?.presentFragment(fragment)
            }

            Logger.info("[Context Menu] Control menu opened for $peerUserId")
        }

        add(ChatContextMenuButton.RESTORE_EXACT) { _ ->
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
