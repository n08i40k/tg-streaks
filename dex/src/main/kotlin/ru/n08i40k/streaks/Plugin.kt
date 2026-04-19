@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.graphics.Color
import android.webkit.ValueCallback
import androidx.room.Room
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.constants.ChatContextMenuButton
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.controller.ServiceMessagesController
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.data.StreakPetLevel
import ru.n08i40k.streaks.database.DatabaseBackupManager
import ru.n08i40k.streaks.database.MIGRATION_1_2
import ru.n08i40k.streaks.database.MIGRATION_2_3
import ru.n08i40k.streaks.database.MIGRATION_3_5
import ru.n08i40k.streaks.database.MIGRATION_5_6
import ru.n08i40k.streaks.database.MIGRATION_6_7
import ru.n08i40k.streaks.database.MIGRATION_7_8
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.impl.AccountSwitchHookBundle
import ru.n08i40k.streaks.hook.impl.UserPutHookBundle
import ru.n08i40k.streaks.hook.impl.PetFabHookBundle
import ru.n08i40k.streaks.hook.impl.PremiumPreviewBottomSheetHookBundle
import ru.n08i40k.streaks.hook.impl.ServiceMessagesHookBundle
import ru.n08i40k.streaks.hook.impl.UpdatesHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ChatAvatarContainerHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ChatMessageCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.DialogCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ProfileActivityHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.UserCellHookBundle
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.registry.LockableActionRegistry
import ru.n08i40k.streaks.registry.LockableCallbackRegistry
import ru.n08i40k.streaks.registry.StreakEmojiRegistry
import ru.n08i40k.streaks.registry.StreakLevelRegistry
import ru.n08i40k.streaks.registry.StreakPetLevelRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.StreakPetUiManager
import ru.n08i40k.streaks.util.AccountTaskRunnerRegistry
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RuntimeGuard
import ru.n08i40k.streaks.util.TaskQueue
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.WidthCache
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicBoolean

typealias LogReceiver = ValueCallback<String>
typealias TranslationResolver = java.util.function.Function<String, String?>

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null

        fun isInjected(): Boolean = INSTANCE != null

        // should not be called from python
        @JvmStatic
        fun getInstance(): Plugin = INSTANCE!!

        @JvmStatic
        fun getBuildDate(): String = Integer.toHexString(BuildConfig.BUILD_TIME.hashCode())

        @JvmStatic
        fun inject(
            logReceiver: LogReceiver,
            translationResolver: TranslationResolver,
            resourcesRootPath: String,
        ) {
            if (INSTANCE != null)
                return

            try {
                INSTANCE = Plugin(
                    logReceiver,
                    translationResolver,
                    ResourcesProvider(resourcesRootPath),
                )
            } catch (e: Throwable) {
                logReceiver.onReceiveValue("Failed to create plugin instance")
                logReceiver.onReceiveValue(e.toString())
                logReceiver.onReceiveValue(e.stackTrace.joinToString("\n"))
                return
            }


            try {
                INSTANCE!!.onInject()
            } catch (e: Throwable) {
                INSTANCE!!.logger.fatal("Failed to inject plugin", e)
            }
        }

        @JvmStatic
        fun invokeChatContextMenuCallback(key: String, id: Long) {
            if (INSTANCE == null)
                throw NullPointerException("Plugin is not injected")

            INSTANCE!!.chatContextMenuCallbackRegistry.get(key).accept(id)
        }

        @JvmStatic
        fun invokeSettingsActionCallback(key: String) {
            if (INSTANCE == null)
                throw NullPointerException("Plugin is not injected")

            INSTANCE!!.settingsActionCallbackRegistry.get(key).run()
        }

        @JvmStatic
        fun registerStreakLevel(
            length: Int,
            color: Color,
            documentId: Long,
            popupResourceName: String,
        ) {
            INSTANCE?.streakLevelRegistry?.register(
                StreakLevel(length, color, documentId, popupResourceName)
            )
        }

        @JvmStatic
        fun registerStreakPetLevel(
            maxPoints: Int,
            imageResourcePath: String,
            gradientStart: String,
            gradientEnd: String,
            petStart: String,
            petEnd: String,
            accent: String,
            accentSecondary: String,
        ) {
            INSTANCE?.streakPetLevelRegistry?.register(
                StreakPetLevel(
                    maxPoints = maxPoints,
                    imageResourcePath = imageResourcePath,
                    gradientStart = gradientStart,
                    gradientEnd = gradientEnd,
                    petStart = petStart,
                    petEnd = petEnd,
                    accent = accent,
                    accentSecondary = accentSecondary,
                )
            )
        }

        @JvmStatic
        fun finalizeInject() {
            INSTANCE?.onFinalizeInject()
        }

        @JvmStatic
        fun setPetFabSizeDp(sizeDp: Int) {
            INSTANCE?.petUiManager?.setFabSizeDp(sizeDp)
        }

        @JvmStatic
        fun eject() {
            // do not run on threads that may be destructed
            AndroidUtilities.runOnUIThread {
                try {
                    INSTANCE?.onEject()
                    INSTANCE = null
                } catch (e: Throwable) {
                    INSTANCE?.logger?.fatal("Failed to eject plugin", e, true)
                }
            }
        }
    }

    val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            logger.fatal("An unknown error occurred in background coroutine scope", exception)
        })

    val uiScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            logger.fatal("An unknown error occurred in ui coroutine scope", exception)
        })

    // database
    private val db: PluginDatabase
    private val databaseBackupManager: DatabaseBackupManager
    private val taskQueue: TaskQueue
    val accountTaskRunnerRegistry: AccountTaskRunnerRegistry

    // helpers
    val logger: Logger
    val translator: Translator
    val resourcesProvider: ResourcesProvider
    val bulletinHelper: BulletinHelper

    // callback registries
    private val chatContextMenuCallbackRegistry = LockableCallbackRegistry()
    private val settingsActionCallbackRegistry = LockableActionRegistry()

    // eject data
    private val hooks: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private val hookBundles: ArrayList<HookBundle> = arrayListOf()

    val streakEmojiRegistry = StreakEmojiRegistry()

    // controllers
    val serviceMessagesController = ServiceMessagesController()
    val streaksController: StreaksController
    val streakPetsController: StreakPetsController
    val petUiManager: StreakPetUiManager

    // registries
    val streakLevelRegistry: StreakLevelRegistry = StreakLevelRegistry()
    val streakPetLevelRegistry: StreakPetLevelRegistry = StreakPetLevelRegistry()
    private val isAutoBackupLoopStarted = AtomicBoolean(false)

    val chatMessageCellWidthCache = WidthCache()

    constructor(
        logReceiver: LogReceiver,
        translationResolver: TranslationResolver,
        resourcesProvider: ResourcesProvider,
    ) {
        // core utils
        this.logger = Logger(logReceiver)
        this.translator = Translator(translationResolver)
        this.resourcesProvider = resourcesProvider
        this.bulletinHelper = BulletinHelper(this.translator)

        // background work
        RuntimeGuard.setLogger(this.logger)
        this.taskQueue = TaskQueue(this.logger)
        this.accountTaskRunnerRegistry = AccountTaskRunnerRegistry(this.logger)

        // database
        this.db = Room.databaseBuilder(
            ApplicationLoader.applicationContext,
            PluginDatabase::class.java,
            "tg-streaks"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_5, MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
            .build()

        this.databaseBackupManager = DatabaseBackupManager(this.db, this.logger::info)

        // controllers
        this.streaksController = StreaksController(this.db, this.logger, this.resourcesProvider)
        this.streakPetsController =
            StreakPetsController(this.logger, this.db, this.streaksController)
        this.petUiManager = StreakPetUiManager(this)
    }

    fun enqueueTask(name: String, callback: suspend () -> Unit) =
        taskQueue.enqueueTask(name, callback)

    fun enqueueAccountInitializationTasks(accountId: Int, reason: String) {
        accountTaskRunnerRegistry.enqueue(
            accountId,
            "prune invalid streaks and pets for account $accountId ($reason)"
        ) {
            streaksController.pruneInvalid(accountId)
            streakPetsController.pruneInvalid(accountId)
        }

        accountTaskRunnerRegistry.enqueue(
            accountId,
            "patch user's emoji statuses for account $accountId ($reason)"
        ) {
            streaksController.patchUsers(accountId)
        }

        accountTaskRunnerRegistry.enqueue(
            accountId,
            "check for updates and update UI for account $accountId ($reason)"
        ) {
            syncPeersUi(streaksController.checkAllForUpdates(accountId))

            streakPetsController.checkAllForUpdates(accountId)
            streaksController.flushCurrentChatPopup()
        }
    }

    private fun enqueueAutoBackupLoopStart(reason: String) {
        accountTaskRunnerRegistry.enqueue(
            UserConfig.selectedAccount,
            "start automatic database backup loop (${reason})"
        ) {
            if (!isAutoBackupLoopStarted.compareAndSet(false, true))
                return@enqueue

            backgroundScope.launch {
                try {
                    databaseBackupManager.runAutoBackupLoop()
                } catch (_: CancellationException) {
                    // Suppress error
                } catch (e: Throwable) {
                    logger.fatal("Automatic database backup loop failed", e)
                    isAutoBackupLoopStarted.set(false)
                }
            }
        }
    }

    private fun onInject() {
        taskQueue.startWorker(backgroundScope)

        registerChatContextMenuCallbacks()
        registerSettingsMenuCallbacks()

        logger.info("Injected!")
    }

    private fun onFinalizeInject() {
        try {
            hookMethods()
        } catch (e: Throwable) {
            logger.fatal("Failed to hook methods!", e)
        }

        enqueueAccountInitializationTasks(UserConfig.selectedAccount, "plugin inject")
        enqueueAutoBackupLoopStart("plugin inject")

        logger.info("Inject finalized!")
    }

    private fun onEject() {
        logger.setFatalSuppression(true)

        taskQueue.stopWorker()
        accountTaskRunnerRegistry.stopAll()

        uiScope.cancel()
        backgroundScope.cancel()

        petUiManager.dismissAll()

        try {
            hooks.forEach { it.unhook() }
            hooks.clear()

            hookBundles.forEach { it.eject() }
            hookBundles.clear()
        } catch (e: Throwable) {
            logger.fatal("Failed to unhook methods!", e)
        }

        try {
            streakEmojiRegistry.restoreAll()

            streaksController.restorePatchedUsers()
        } catch (e: Throwable) {
            logger.fatal("Failed to restore original SwapAnimatedEmojiDrawable!", e)
        }

        chatContextMenuCallbackRegistry.clear()
        settingsActionCallbackRegistry.clear()

        try {
            db.close()
        } catch (e: Throwable) {
            logger.fatal("Failed to close database on eject", e)
        }

        logger.info("Ejected!")
    }

    suspend fun syncPeerUi(accountId: Int, peerUserId: Long) {
        streaksController.syncUserState(accountId, peerUserId)

        AndroidUtilities.runOnUIThread {
            streakEmojiRegistry.refreshByPeerUserId(peerUserId)
            streakEmojiRegistry.refreshDialogCells()
        }
    }

    private suspend fun syncPeersUi(targets: Iterable<StreaksController.UiSyncTarget>) {
        val syncTargets = targets.distinct()

        syncTargets.forEach { streaksController.syncUserState(it.accountId, it.peerUserId) }

        AndroidUtilities.runOnUIThread {
            streakEmojiRegistry.refreshAll()
            return@runOnUIThread
        }
    }

    fun enqueueRebuildForPeer(
        accountId: Int,
        peerUserId: Long,
        onComplete: (() -> Unit)? = null,
    ) {
        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

        if (peerUser == null || !isPeerValid(peerUser)) {
            bulletinHelper.showTranslated(TranslationKey.Status.Info.CHAT_PRIVATE_USERS_ONLY)
            return
        }

        accountTaskRunnerRegistry.enqueue(
            accountId,
            "rebuild streak for $accountId:$peerUserId"
        ) {
            streaksController.rebuild(
                accountId,
                peerUser
            ) { progress -> progress.showBulletin() }

            syncPeerUi(accountId, peerUserId)

            val rebuiltStreak = streaksController.get(accountId, peerUserId)

            if (rebuiltStreak != null) {
                bulletinHelper.showTranslated(
                    TranslationKey.Rebuild.Streak.SUMMARY_CHAT,
                    mapOf(
                        "peer_name" to (peerUser.username?.takeIf { it.isNotBlank() }
                            ?.let { "@$it" }
                            ?: UserObject.getUserName(peerUser).takeIf { it.isNotBlank() }
                            ?: peerUser.id.toString()),
                        "days" to rebuiltStreak.length.toString(),
                        "revives" to rebuiltStreak.revivesCount.toString(),
                    ),
                    "msg_retry"
                )
            }

            if (onComplete != null) {
                AndroidUtilities.runOnUIThread { onComplete() }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun registerChatContextMenuCallbacks() {
        fun add(key: String, callback: (Long) -> Unit) {
            chatContextMenuCallbackRegistry.register(key) {
                try {
                    callback(it)
                } catch (e: Throwable) {
                    logger.fatal("An error occurred while handling context menu entry touch", e)
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

            logger.info("[Context Menu] Rebuild clicked on $peerUserId")
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

                streakPetsController.rebuild(accountId, peerUser) { progress ->
                    progress.showBulletin()
                }
            }

            logger.info("[Context Menu] Rebuild pet clicked on $peerUserId")
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

            logger.info("[Context Menu] Toggle pet fab clicked on $peerUserId; enabled=$petFabEnabled")
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
                            .setTitle(translator.translate(TranslationKey.Dialog.CreatePet.TITLE))
                            .setMessage(translator.translate(TranslationKey.Dialog.CreatePet.MESSAGE))
                            .setPositiveButton(
                                translator.translate(TranslationKey.Dialog.CreatePet.CONFIRM)
                            ) { _, _ ->
                                streaksController.setServiceMessagesEnabled(
                                    accountId,
                                    peerUserId,
                                    true
                                )
                                serviceMessagesController.sendPetInvite(accountId, peerUserId)
                            }
                            .setNegativeButton(
                                translator.translate(TranslationKey.Dialog.CreatePet.CANCEL)
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

            logger.info("[Context Menu] Create pet clicked on $peerUserId")
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
                logger.info("[Context Menu] Go-to-streak-start failed: no chat context for $peerUserId")
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
                                    logger.info(
                                        "[Context Menu] Go-to-streak-start scroll failed for $peerUserId: ${e.message}"
                                    )
                                }
                            }

                            chatActivity.jumpToDate(jumpTs)
                            bulletinHelper.showTranslated(
                                TranslationKey.Status.Info.STREAK_START_MESSAGE_NOT_FOUND
                            )
                        } catch (e: Throwable) {
                            logger.fatal(
                                "Go-to-streak-start failed for peer $peerUserId",
                                e
                            )
                            bulletinHelper.showTranslated(
                                TranslationKey.Status.Error.STREAK_JUMP_TO_START_FAILED
                            )
                        }
                    }
                } catch (e: Throwable) {
                    logger.fatal("Go-to-streak-start lookup failed for peer $peerUserId", e)
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.STREAK_JUMP_TO_START_FAILED)
                }
            }

            logger.info("[Context Menu] Go-to-streak-start clicked on $peerUserId")
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

            logger.info("[Context Menu] Toggle service messages clicked on $peerUserId; enabled=$enabled")
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

            logger.info("[Context Menu] Debug-create clicked on ${peerUser.id}")
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

            logger.info("[Context Menu] Debug-upgrade clicked on ${peerUser.id}")
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

            logger.info("[Context Menu] Debug-freeze clicked on ${peerUser.id}")
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

            logger.info("[Context Menu] Debug-kill clicked on ${peerUser.id}")
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

            logger.info("[Context Menu] Debug-delete clicked on ${peerUser.id}")
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

            logger.info("[Context Menu] Debug-delete-pet clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_CRASH) { _ ->
            throw RuntimeException("Crash button was pressed")
        }

        chatContextMenuCallbackRegistry.freeze()
    }

    private fun registerSettingsMenuCallbacks() {
        fun add(key: String, callback: () -> Unit) {
            settingsActionCallbackRegistry.register(key) {
                try {
                    callback()
                } catch (e: Throwable) {
                    logger.fatal("An error occurred while handling settings action", e)
                }
            }
        }


        add(SettingsActionButton.REBUILD_ALL) {
            val accountId = UserConfig.selectedAccount

            if (streaksController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.Status.Info.REBUILD_ALREADY_RUNNING)
                return@add
            }

            bulletinHelper.showTranslated(
                TranslationKey.Status.Info.REBUILD_STARTED_ALL_CHATS,
                "msg_retry"
            )

            accountTaskRunnerRegistry.enqueue(accountId, "rebuild all streaks for $accountId") {
                try {
                    val result =
                        streaksController.rebuildAll(accountId) { index, total, _, progress ->
                            bulletinHelper.showTranslated(
                                TranslationKey.Rebuild.Streak.PROGRESS_ALL_CHATS,
                                mapOf(
                                    "peer_name" to progress.peerUser.label,
                                    "days_checked" to progress.daysChecked.toString(),
                                    "checked_chats" to (index + 1).toString(),
                                    "total_chats" to total.toString(),
                                ),
                                "msg_retry"
                            )
                        }

                    syncPeersUi(result.uiSyncTargets)

                    bulletinHelper.showTranslated(
                        TranslationKey.Rebuild.Streak.SUMMARY_ALL_CHATS,
                        mapOf("checked" to result.totalChats.toString()),
                        "msg_retry"
                    )
                } catch (e: Throwable) {
                    logger.fatal("Failed to rebuild all private chats for account $accountId", e)
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.REBUILD_FAILED_CHECK_LOGS)
                }
            }
        }

        add(SettingsActionButton.EXPORT_BACKUP_NOW) {
            enqueueTask("export database backup") {
                try {
                    val backup = databaseBackupManager.exportNow()
                    bulletinHelper.showTranslated(
                        TranslationKey.Status.Success.BACKUP_EXPORTED,
                        mapOf("name" to backup.name),
                        "msg_save"
                    )
                } catch (e: Throwable) {
                    logger.fatal("Failed to export database backup", e)
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.BACKUP_EXPORT_FAILED)
                }
            }
        }

        settingsActionCallbackRegistry.freeze()
    }

    private fun hookMethods() {
        fun add(method: Member, hook: XC_MethodHook) {
            hooks.add(XposedBridge.hookMethod(method, hook))
        }

        fun before(method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
            add(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            callback(param)
                        } catch (e: Throwable) {
                            logger.fatal(
                                "An error occurred in $method before-call hook!",
                                e
                            )
                        }
                    }
                }
            )
        }

        fun after(method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
            add(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            callback(param)
                        } catch (e: Throwable) {
                            logger.fatal(
                                "An error occurred in $method after-call hook!",
                                e
                            )
                        }
                    }
                }
            )
        }

        hookBundles.addAll(
            listOf(
                ChatAvatarContainerHookBundle(),
                ChatMessageCellHookBundle(),
                DialogCellHookBundle(),
                ProfileActivityHookBundle(),
                UserCellHookBundle(),
                AccountSwitchHookBundle(),
                UserPutHookBundle(),
                PetFabHookBundle(),
                PremiumPreviewBottomSheetHookBundle(),
                ServiceMessagesHookBundle(),
                UpdatesHookBundle()
            )
        )

        hookBundles.forEach { it.inject(::before, ::after) }
    }
}
