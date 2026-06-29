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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
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
import ru.n08i40k.streaks.database.MIGRATION_8_9
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
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
import ru.n08i40k.streaks.hook.impl.emoji.ProfileSearchCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.StatusBadgeComponentHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.UserCellHookBundle
import ru.n08i40k.streaks.registry.LockableActionRegistry
import ru.n08i40k.streaks.registry.LockableCallbackRegistry
import ru.n08i40k.streaks.registry.StreakEmojiRegistry
import ru.n08i40k.streaks.registry.StreakLevelRegistry
import ru.n08i40k.streaks.registry.StreakPetLevelRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.StreakPetUiManager
import ru.n08i40k.streaks.util.AccountTaskRunnerRegistry
import ru.n08i40k.streaks.util.BadgesCompat
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RebuildNotificationHelper
import ru.n08i40k.streaks.util.RuntimeGuard
import ru.n08i40k.streaks.util.StreakAlertNotificationHelper
import ru.n08i40k.streaks.util.TaskQueue
import ru.n08i40k.streaks.util.Translator
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

    // database
    private val db: PluginDatabase
    internal val databaseBackupManager: DatabaseBackupManager
    private val taskQueue: TaskQueue
    val accountTaskRunnerRegistry: AccountTaskRunnerRegistry

    // helpers
    val logger: Logger
    val translator: Translator
    val resourcesProvider: ResourcesProvider
    val bulletinHelper: BulletinHelper
    val rebuildNotificationHelper: RebuildNotificationHelper
    val alertNotificationHelper: StreakAlertNotificationHelper

    // callback registries
    internal val chatContextMenuCallbackRegistry = LockableCallbackRegistry()
    internal val settingsActionCallbackRegistry = LockableActionRegistry()

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
        this.rebuildNotificationHelper = RebuildNotificationHelper(this.translator)
        this.alertNotificationHelper = StreakAlertNotificationHelper(this.translator)

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
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()

        this.databaseBackupManager = DatabaseBackupManager(this.db, this.logger::info)

        // controllers
        this.streaksController = StreaksController(this.db, this.logger, this.resourcesProvider, this.alertNotificationHelper, this.serviceMessagesController)
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
            rebuildNotificationHelper.beginCheckNotification()

            val syncTargets = streaksController.checkAllForUpdates(
                accountId
            ) { index, total, peerName, daysChecked, totalDays ->
                rebuildNotificationHelper.updateCheckProgress(
                    index, total, peerName, daysChecked, totalDays
                )
            }

            rebuildNotificationHelper.cancelCheckProgress()

            syncPeersUi(syncTargets)
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
        BadgesCompat.takeException()?.let {
            logger.fatal("Failed to init BadgesCompat", it)
            return
        }

        taskQueue.startWorker(backgroundScope)

        ChatContextMenuActions(this).register()
        SettingsMenuActions(this).register()

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

        backgroundScope.cancel()

        petUiManager.dismissAll()
        rebuildNotificationHelper.cancelAll()

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

    internal suspend fun syncPeersUi(targets: Iterable<StreaksController.UiSyncTarget>) {
        val syncTargets = targets.distinct()

        syncTargets.forEach { streaksController.syncUserState(it.accountId, it.peerUserId) }

        AndroidUtilities.runOnUIThread {
            streakEmojiRegistry.refreshAll()
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
            val peerName = peerUser.label

            streaksController.rebuild(accountId, peerUser) { progress ->
                rebuildNotificationHelper.updateSingleStreakProgress(peerName, progress.daysChecked)
            }

            syncPeerUi(accountId, peerUserId)

            val rebuiltStreak = streaksController.get(accountId, peerUserId)

            if (rebuiltStreak != null) {
                rebuildNotificationHelper.completeSingleStreak(
                    peerName,
                    rebuiltStreak.length,
                    rebuiltStreak.revivesCount,
                )
            } else {
                rebuildNotificationHelper.cancelSingleProgress()
            }

            if (onComplete != null) {
                AndroidUtilities.runOnUIThread { onComplete() }
            }
        }
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
                ProfileSearchCellHookBundle(),
                StatusBadgeComponentHookBundle(),
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
