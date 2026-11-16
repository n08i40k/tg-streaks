package ru.n08i40k.streaks.menu

import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.shim.menu.MenuScope
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.ui.StreakControlFragment
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.presentFragment
import ru.n08i40k.streaks.util.runOnMainThread
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class ChatActionMenu(
    private val plugin: Plugin,
    private val debug: Boolean,
) {
    private class Target(
        val accountId: Int,
        val peerUserId: Long,
        val chatActivity: ChatActivity?,
    )

    fun MenuScope.build() {
        item(
            id = "controlMenu",
            title = Strings.menu_chat_control_panel_title(),
            description = Strings.menu_chat_control_panel_description(),
            icon = "msg_settings",
            priority = 1001,
            action = ::openControlPanel,
        )

        item(
            id = "restoreExact",
            title = Strings.menu_chat_restore_streak_exact_title(),
            description = Strings.menu_chat_restore_streak_exact_description(),
            icon = "msg_reactions",
            priority = 999,
            action = ::openRestoreCalendar,
        )

        if (!debug)
            return

        item(
            id = "debug.create",
            title = Strings.menu_debug_create_streak_title(),
            description = Strings.menu_debug_create_streak_description(),
            priority = 993,
        ) { target ->
            debugTask(target, "create debug streak") { accountId, peerUserId ->
                plugin.streaksController.debugSetThreeDayStreak(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_set_to_3_days(),
                    "msg_reactions"
                )
            }
        }

        item(
            id = "debug.upgrade",
            title = Strings.menu_debug_upgrade_streak_title(),
            description = Strings.menu_debug_upgrade_streak_description(),
            priority = 992,
        ) { target ->
            debugTask(target, "upgrade debug streak") { accountId, peerUserId ->
                val newLength = plugin.streaksController.debugUpgradeStreak(accountId, peerUserId)
                    ?: return@debugTask

                BulletinHelper.show(
                    Strings.status_success_debug_streak_upgraded(newLength),
                    "msg_reactions"
                )
            }
        }

        item(
            id = "debug.freeze",
            title = Strings.menu_debug_freeze_streak_title(),
            description = Strings.menu_debug_freeze_streak_description(),
            priority = 991,
        ) { target ->
            debugTask(target, "freeze debug streak") { accountId, peerUserId ->
                plugin.streaksController.debugFreezeStreak(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_frozen(),
                    "msg_reactions"
                )
            }
        }

        item(
            id = "debug.kill",
            title = Strings.menu_debug_kill_streak_title(),
            description = Strings.menu_debug_kill_streak_description(),
            priority = 990,
        ) { target ->
            debugTask(target, "kill debug streak") { accountId, peerUserId ->
                plugin.streaksController.debugMarkDead(accountId, peerUserId)

                BulletinHelper.show(
                    Strings.status_success_debug_streak_marked_dead(),
                    "msg_reactions"
                )
            }
        }

        item(
            id = "debug.delete",
            title = Strings.menu_debug_delete_streak_title(),
            description = Strings.menu_debug_delete_streak_description(),
            priority = 989,
        ) { target ->
            debugTask(target, "delete debug streak") { accountId, peerUserId ->
                if (!plugin.streaksController.delete(accountId, peerUserId)) {
                    BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())
                    return@debugTask
                }

                BulletinHelper.show(
                    Strings.status_success_debug_streak_deleted(),
                    "msg_reactions"
                )
            }
        }

        item(
            id = "debug.deletePet",
            title = Strings.menu_debug_delete_pet_title(),
            description = Strings.menu_debug_delete_pet_description(),
            priority = 988,
        ) { target ->
            debugTask(target, "delete debug pet") { accountId, peerUserId ->
                if (!plugin.streakPetsController.delete(accountId, peerUserId)) {
                    BulletinHelper.show(Strings.status_info_pet_not_created_for_chat())
                    return@debugTask
                }

                BulletinHelper.show(Strings.status_success_debug_pet_deleted(), "msg_reactions")
            }
        }

        item(
            id = "debug.crash",
            title = Strings.menu_debug_crash_plugin_title(),
            description = Strings.menu_debug_crash_plugin_description(),
            priority = 987,
        ) {
            throw RuntimeException("Crash button was pressed")
        }
    }

    private fun MenuScope.item(
        id: String,
        title: String,
        description: String,
        icon: String? = null,
        priority: Int,
        action: (Target) -> Unit,
    ) = inChatActionMenu(id) {
        this.text = title
        this.subtext = description
        this.icon = icon
        this.priority = priority

        onClick { context ->
            Logger.tryOrFatal("handle chat menu item \"$id\"") {
                val target = resolveTarget(context)

                if (target == null) {
                    Logger.info("[Chat menu] No dialog in click context of $id: $context")
                    BulletinHelper.show(Strings.status_error_chat_open_context_failed())

                    return@tryOrFatal
                }

                action(target)
            }
        }
    }

    private fun resolveTarget(context: Map<String, Any>): Target? {
        val fragment = context["fragment"] as? ChatActivity
            ?: LaunchActivity.getSafeLastFragment() as? ChatActivity

        val peerUserId = (context["dialog_id"] as? Number)
            ?.toLong()
            ?.takeIf { it != 0L }
            ?: fragment
                ?.dialogId
                ?.takeIf { it != 0L }
            ?: return null

        return Target(
            accountId = UserConfig.selectedAccount,
            peerUserId = peerUserId,
            chatActivity = fragment?.takeIf { it.dialogId == peerUserId },
        )
    }

    private fun debugTask(
        target: Target,
        name: String,
        block: suspend (accountId: Int, peerUserId: Long) -> Unit,
    ) = with(target) {
        if (!isPeerValid(accountId, peerUserId)) {
            BulletinHelper.show(Strings.status_info_debug_private_users_only())
            return@with
        }

        AccountTaskExecutor.enqueue(accountId, "$name for $accountId:$peerUserId") {
            block(accountId, peerUserId)
        }

        Logger.info("[Chat menu] $name clicked on $peerUserId")
    }

    private fun openControlPanel(target: Target): Unit = with(plugin) {
        val accountId = target.accountId
        val peerUserId = target.peerUserId

        val ownerUserId = UserConfig.getInstance(accountId)
            .clientUserId

        if (!isPeerValid(accountId, peerUserId)) {
            BulletinHelper.show(Strings.status_info_chat_private_users_only())
            return
        }

        val viewModel = object : StreakControlFragment.ViewModel {
            private val stateFlow = MutableStateFlow(StreakControlFragment.ViewState())

            init {
                AccountTaskExecutor.enqueue(
                    accountId,
                    "load control state for $accountId:$peerUserId",
                    ::refreshState
                )
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
                            serviceMessageCategoriesController
                                .isEnabled(ownerUserId, peerUserId, category)
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

                    val peerUser = MessagesController.getInstance(accountId)
                        .getUser(peerUserId)

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
                    val swapped = databaseBackupManager.exportSwappedNow(ownerUserId, peerUserId)

                    serviceMessagesController
                        .sendSyncOffer(accountId, peerUserId, Uri.fromFile(swapped))
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
                    serviceMessageCategoriesController
                        .setEnabled(ownerUserId, peerUserId, categoryName, value)

                    refreshState()
                }
            }

            override fun setPetFabEnabled(value: Boolean) {
                AccountTaskExecutor.enqueue(
                    accountId,
                    "set pet fab enabled for $accountId:$peerUserId"
                ) {
                    streakPetsController
                        .setFabEnabled(accountId, peerUserId, value)

                    refreshState()
                }
            }

            override fun rebuildBoth() {
                AccountTaskExecutor.enqueue(
                    accountId,
                    "rebuild streak+pet for $accountId:$peerUserId"
                ) {
                    val peerUser = MessagesController.getInstance(accountId)
                        .getUser(peerUserId)
                        ?: return@enqueue

                    streaksController.rebuild(accountId, peerUser)

                    if (streaksController.exists(accountId, peerUserId) &&
                        streakPetsController.exists(accountId, peerUserId)
                    ) {
                        streakPetsController.rebuild(accountId, peerUser)
                    }

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
                    val peerUser = MessagesController.getInstance(accountId)
                        .getUser(peerUserId)
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

                    if (!streaksController.restore(accountId, peerUserId, Clock.System.now())) {
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
                    delay(2000.milliseconds)

                    runOnMainThread {
                        val chatActivity =
                            (LaunchActivity.getSafeLastFragment() as? ChatActivity)
                                ?.takeIf { it.dialogId == peerUserId }

                        if (chatActivity == null) {
                            BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                            return@runOnMainThread
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

        presentFragment(StreakControlFragment(viewModel))

        Logger.info("[Chat menu] Control menu opened for $peerUserId")
    }

    private fun openRestoreCalendar(target: Target): Unit = with(plugin) {
        val chatActivity = target.chatActivity
            ?: run {
                BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                return
            }

        val accountId = target.accountId
        val peerUserId = target.peerUserId

        val adapter = object : FixupCalendarActivity.Adapter {
            override suspend fun getSnapshot() =
                streaksController.getCalendarInteractionSnapshot(accountId, peerUserId)

            override suspend fun analyzeTap(day: LocalDate) =
                streaksController.analyzeCalendarTap(accountId, peerUserId, day)

            override suspend fun addManualRestore(day: LocalDate) =
                streaksController.addManualCalendarRestore(accountId, peerUserId, day)

            override fun rebuild(onComplete: () -> Unit) =
                enqueueRebuildForPeer(accountId, peerUserId, onComplete)
        }

        runOnMainThread {
            val fragment = FixupCalendarActivity.create(peerUserId, adapter, chatActivity)

            chatActivity.presentFragment(fragment)
        }
    }
}
