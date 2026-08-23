package ru.n08i40k.streaks

import android.content.Context
import android.content.SharedPreferences
import android.webkit.ValueCallback
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.room.Room
import androidx.room.useWriterConnection
import de.comahe.i18n4k.config.I18n4kConfigDefault
import de.comahe.i18n4k.createLocale
import de.comahe.i18n4k.i18n4k
import de.comahe.i18n4k.messages.formatter.MessageFormatterDefault
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.jetbrains.annotations.Blocking
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.controller.PluginRelationController
import ru.n08i40k.streaks.controller.ServiceMessageCategoriesController
import ru.n08i40k.streaks.controller.ServiceMessagesController
import ru.n08i40k.streaks.controller.StreakEmojiPacksController
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.controller.StreakPopupController
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.controller.TimeZonesController
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.database.DatabaseBackupManager
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.buildPluginDatabase
import ru.n08i40k.streaks.extension.diff
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.now
import ru.n08i40k.streaks.extension.onEachOnMainThread
import ru.n08i40k.streaks.extension.onEachWith
import ru.n08i40k.streaks.extension.onEachWithOnMainThread
import ru.n08i40k.streaks.extension.onEachWithOnMainThreadBlocking
import ru.n08i40k.streaks.extension.resolveLanguageCode
import ru.n08i40k.streaks.extension.toLocalDate
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import ru.n08i40k.streaks.hook.impl.AccountSwitchHookBundle
import ru.n08i40k.streaks.hook.impl.PetFabHookBundle
import ru.n08i40k.streaks.hook.impl.PremiumPreviewBottomSheetHookBundle
import ru.n08i40k.streaks.hook.impl.ServiceMessagesHookBundle
import ru.n08i40k.streaks.hook.impl.UpdatesHookBundle
import ru.n08i40k.streaks.hook.impl.UserPutHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ChatAvatarContainerHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ChatMessageCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.DialogCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ProfileActivityHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.ProfileSearchCellHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.StatusBadgeComponentHookBundle
import ru.n08i40k.streaks.hook.impl.emoji.UserCellHookBundle
import ru.n08i40k.streaks.i18n.MessagePluralFormatter
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.PluginBadges
import ru.n08i40k.streaks.registry.LockableActionRegistry
import ru.n08i40k.streaks.registry.LockableCallbackRegistry
import ru.n08i40k.streaks.registry.StreakEmojiRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.StreakPetUiManager
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BadgesCompat
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.CheckNotificationHelper
import ru.n08i40k.streaks.util.DatabaseTransactor
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RateLimitContext
import ru.n08i40k.streaks.util.RefCounter
import ru.n08i40k.streaks.util.StreakAlertNotificationHelper
import ru.n08i40k.streaks.util.TaskQueue
import ru.n08i40k.streaks.util.UserPatcher
import ru.n08i40k.streaks.util.runOnMainThread
import java.lang.reflect.Member
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.time.Instant

typealias LogReceiver = ValueCallback<String>

class Plugin {
    @Suppress("unused")
    companion object {
        const val ID = "tg-streaks"

        private const val HANDLE_KEY = "ru.n08i40k.streaks.handle"

        @Volatile
        private var WAS_INJECTED = false

        @Volatile
        private var INSTANCE: Plugin? = null

        private var VERSION: String? = null

        fun isInjected(): Boolean = INSTANCE != null

        internal fun getInstance(): Plugin = INSTANCE!!

        @JvmStatic
        fun getBuildDate(): String = Instant
            .fromEpochMilliseconds(BuildConfig.BUILD_TIME.toLong())
            .toString()

        @JvmStatic
        fun getVersion(): String? = VERSION

        @Synchronized
        @Blocking
        @JvmStatic
        fun inject(
            version: String,
            logReceiver: LogReceiver,
            resourcesRootPath: String,
        ) {
            if (INSTANCE != null)
                return

            if (WAS_INJECTED)
                throw IllegalStateException("Cannot inject plugin from same class-loader twice")

            VERSION = version
            WAS_INJECTED = true

            Logger.setReceiver(logReceiver)

            val props = System.getProperties()

            // prevent two plugin injects concurrently (from different class-loaders)
            synchronized(props) {
                @Suppress("UNCHECKED_CAST")
                (props.put(HANDLE_KEY, Supplier { ejectPromise() }) as? Supplier<Thread>)
                    ?.apply {
                        Logger.info("Plugin is probably injected in different class loader!")

                        Logger.info("Ejecting old plugin...")
                        get().join()
                    }


                i18n4k = I18n4kConfigDefault().apply {
                    locale = createLocale(
                        LocaleController
                            .getInstance()
                            .resolveLanguageCode()
                    )
                }
                MessageFormatterDefault.registerMessageValueFormatters(MessagePluralFormatter)

                Logger.tryOrFatal("create and inject plugin") {
                    val plugin = Plugin(ResourcesProvider(resourcesRootPath))
                        .also { INSTANCE = it }

                    plugin.onInject()
                }
            }
        }

        @JvmStatic
        fun invokeChatContextMenuCallback(key: String, id: Long) = with(INSTANCE!!) {
            chatContextMenuCallbackRegistry.get(key).accept(id)
        }

        @JvmStatic
        fun invokeSettingsActionCallback(key: String) = with(INSTANCE!!) {
            settingsActionCallbackRegistry.get(key).run()
        }

        @Blocking
        @Synchronized
        @JvmStatic
        fun finalizeInject() {
            // safely return as eject was called before finalizeInject
            if (WAS_INJECTED && INSTANCE == null)
                return

            // NPE is a bug, then it should not be silenced
            INSTANCE!!.onFinalizeInject()
        }

        @JvmStatic
        fun setPetFabSizeDp(sizeDp: Int) = with(INSTANCE!!) {
            petUiManager.setFabSizeDp(sizeDp)
        }

        @Synchronized
        private fun ejectSynchronized() {
            Logger.tryOrFatal("Failed to eject plugin") {
                INSTANCE?.onEject()
            }

            INSTANCE = null
        }

        @AnyThread
        private fun ejectPromise(): Thread =
            thread(
                contextClassLoader = Plugin::class.java.classLoader,
                block = ::ejectSynchronized
            )

        @AnyThread
        @JvmStatic
        fun eject() {
            ejectPromise()
        }

        @AnyThread
        @JvmStatic
        fun getSharedPrefs(): SharedPreferences =
            ApplicationLoader.applicationContext.getSharedPreferences(
                ID,
                Context.MODE_PRIVATE
            )

        fun coroutineScope(): CoroutineScope =
            INSTANCE!!.backgroundScope

        fun childCoroutineScope(): CoroutineScope =
            INSTANCE!!.backgroundScope.coroutineContext.let { CoroutineScope(it + SupervisorJob(it.job)) }
    }

    val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            Logger.fatal("An unknown error occurred in background coroutine scope", exception)
        })

    // database
    private val db: PluginDatabase = Room.buildPluginDatabase()

    internal val databaseBackupManager: DatabaseBackupManager
    private val taskQueue: TaskQueue

    // helpers
    val resourcesProvider: ResourcesProvider
    val alertNotificationHelper: StreakAlertNotificationHelper

    // callback registries
    internal val chatContextMenuCallbackRegistry = LockableCallbackRegistry()
    internal val settingsActionCallbackRegistry = LockableActionRegistry()

    // eject data
    val hooks: ArrayList<XC_MethodHook.Unhook> = arrayListOf()

    val streakEmojiRegistry = StreakEmojiRegistry()

    // view cache holds resolved theme colors
    private val themeObserver =
        NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.didSetNewTheme)
                refreshStreakViews()
        }

    // controllers
    val serviceMessagesController = ServiceMessagesController()
    val streakEmojiPacksController: StreakEmojiPacksController
    val streaksController: StreaksController
    val streakPetsController: StreakPetsController
    val timeZonesController: TimeZonesController
    val pluginRelationController: PluginRelationController
    val serviceMessageCategoriesController: ServiceMessageCategoriesController
    val petUiManager: StreakPetUiManager

    constructor(resourcesProvider: ResourcesProvider) {
        try {
            this.resourcesProvider = resourcesProvider
            this.alertNotificationHelper = StreakAlertNotificationHelper()

            // abstract transactions from database instance
            val transactor = DatabaseTransactor(this.db)

            // background work
            this.taskQueue = TaskQueue()

            // database
            this.databaseBackupManager = DatabaseBackupManager(this.db, Logger::info)

            // controllers
            this.timeZonesController =
                TimeZonesController(this.db.peerTimeZoneDao())

            this.streakEmojiPacksController =
                StreakEmojiPacksController(this.db.streakEmojiPackDao())

            this.streaksController =
                StreaksController(
                    transactor,
                    this.db.streakDao(),
                    this.db.streakRestoreDao(),
                    StreakPopupController(
                        this.db.scheduledStreakPopupDao(),
                        this.resourcesProvider
                    ),
                    this.timeZonesController,
                )

            this.streakPetsController =
                StreakPetsController(
                    transactor,
                    this.db.streakPetDao(),
                    this.db.streakPetTaskDao(),
                    this.streaksController,
                    this.timeZonesController
                )

            this.serviceMessageCategoriesController =
                ServiceMessageCategoriesController(this.db.serviceMessageCategoriesDao())

            this.pluginRelationController =
                PluginRelationController(
                    this.db.pluginRelationDao(),
                    this.serviceMessageCategoriesController
                )

            this.petUiManager =
                StreakPetUiManager()
        } catch (e: Throwable) {
            this.db.close()
            throw e
        }
    }

    @UiThread
    private fun refreshStreakViews() {
        streaksController.refreshViewCache()
        streakEmojiRegistry.refreshAll()
        streakEmojiRegistry.refreshDialogCells()
    }

    @OptIn(FlowPreview::class)
    private fun subscribeToEvents() {
        // streak ui patches/transitions
        EventBus.stream
            .filterIsInstance<PluginEvent.ActiveStreakEmojiPackChanged>()
            .onEachOnMainThread { refreshStreakViews() }
            .launchIn(backgroundScope)

        EventBus.stream
            .filterIsInstance<PluginEvent.StreakEvent>()
            .onEachWithOnMainThreadBlocking {
                streakEmojiRegistry.refreshByPeerUserId(peerUserId)

                when (this) {
                    is PluginEvent.StreakCreatedEvent -> {
                        if (!record.isVisible)
                            return@onEachWithOnMainThreadBlocking

                        UserPatcher.patchUser(accountId, peerUserId)

                        streaksController.enqueuePopupForTransition(
                            accountId,
                            peerUserId,
                            null,
                            record
                        )
                    }

                    is PluginEvent.StreakGrowUpEvent -> {
                        UserPatcher.patchUser(accountId, peerUserId)

                        streaksController.enqueuePopupForTransition(
                            accountId,
                            peerUserId,
                            sourceRecord,
                            targetRecord
                        )
                    }

                    is PluginEvent.StreakRebuiltEvent,
                    is PluginEvent.StreakRestoredEvent -> {
                        if (!record.isVisible)
                            return@onEachWithOnMainThreadBlocking

                        UserPatcher.patchUser(accountId, peerUserId)
                    }

                    is PluginEvent.StreakDeletedEvent,
                    is PluginEvent.StreakLostEvent -> {
                        UserPatcher.restoreUser(accountId, peerUserId)

                        alertNotificationHelper.cancelNearDeath(peerUserId)

                        // as we don't need to notify about manual streak deletion
                        if (this is PluginEvent.StreakLostEvent) {
                            alertNotificationHelper.showDeath(
                                peerUserId,
                                MessagesController.getInstance(accountId)
                                    .getUser(peerUserId)?.label
                                    ?: peerUserId.toString(),
                                record.length
                            )
                        }
                    }
                }
            }
            .launchIn(backgroundScope)

        // streak dependents
        EventBus.stream
            .filterIsInstance<PluginEvent.StreakDeletedEvent>()
            .onEachWith {
                streakPetsController.delete(accountId, record.peerUserId, timestamp, true)
            }
            .launchIn(backgroundScope)

        // debounced dialog cells refresh
        EventBus.stream
            .filterIsInstance<PluginEvent.StreakEvent>()
            .debounce(100)
            .onEachOnMainThread { streakEmojiRegistry.refreshDialogCells() }
            .launchIn(backgroundScope)

        // sync
        EventBus.stream
            .filterIsInstance<PluginEvent.SyncDatabaseSnapshotAppliedEvent>()
            .onEachWithOnMainThread {
                if (hasVisibleStreak)
                    UserPatcher.patchUser(accountId, peerUserId)
                else
                    UserPatcher.restoreUser(accountId, peerUserId)

                alertNotificationHelper.cancelNearDeath(peerUserId)
                alertNotificationHelper.cancelDeath(peerUserId)

                petUiManager.refreshFabForOpenChat()
                petUiManager.refreshOpenedDialog(accountId, peerUserId)

                streakEmojiRegistry.refreshDialogCells()
            }
            .launchIn(backgroundScope)

        // pre-death notification
        EventBus.stream
            .filterIsInstance<PluginEvent.StreakDeathWarningEvent>()
            .onEachWith {
                if (active) {
                    alertNotificationHelper.showNearDeath(
                        peerUserId,
                        peerName,
                        streak.length,
                        timeUntilDeathSeconds
                    )
                } else {
                    alertNotificationHelper.cancelNearDeath(peerUserId)
                }
            }
            .launchIn(backgroundScope)

        // streak pet fab
        EventBus.stream
            .filterIsInstance<PluginEvent.StreakPetEvent>()
            .onEachWithOnMainThread {
                petUiManager.refreshFabForOpenChat()
                petUiManager.refreshOpenedDialog(accountId, peerUserId)
            }
            .launchIn(backgroundScope)

        // service messages
        EventBus.stream
            .filterIsInstance<PluginEvent.PeerEvent>()
            .onEachWith {
                when (this) {
                    is PluginEvent.StreakGrowUpEvent -> {
                        if (LocalDate.now(record.timeZone)
                                .diff(timestamp.toLocalDate(record.timeZone)) > 2
                        )
                            return@onEachWith

                        val allowSend = serviceMessageCategoriesController.isEnabled(
                            record.ownerUserId,
                            record.peerUserId,
                            ServiceMessageCategory.LEVEL_UP
                        )

                        if (!allowSend)
                            return@onEachWith

                        val targetLevelLength = targetRecord.level.length

                        if (targetLevelLength == targetRecord.length
                            && targetLevelLength == StreakLevel.findFirstVisible().length
                        ) {
                            serviceMessagesController
                                .sendCreation(accountId, peerUserId)
                            return@onEachWith
                        }

                        if (targetRecord.level <= sourceRecord.level)
                            return@onEachWith

                        serviceMessagesController
                            .sendUpgrade(accountId, peerUserId, targetRecord.level.length)
                    }

                    is PluginEvent.StreakLostEvent -> {
                        if (LocalDate.now(record.timeZone)
                                .diff(timestamp.toLocalDate(record.timeZone)) > 1
                        )
                            return@onEachWith

                        val allowSend = serviceMessageCategoriesController.isEnabled(
                            record.ownerUserId,
                            record.peerUserId,
                            ServiceMessageCategory.LIFECYCLE
                        )

                        if (!allowSend)
                            return@onEachWith

                        serviceMessagesController
                            .sendDeath(accountId, peerUserId)
                    }

                    is PluginEvent.StreakRestoredEvent -> {
                        if (byPeer)
                            return@onEachWith

                        if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                            return@onEachWith

                        serviceMessagesController
                            .sendRestore(accountId, peerUserId)
                    }

                    is PluginEvent.StreakPetRenamedEvent -> {
                        if (by != PluginEvent.StreakPetRenamedEvent.By.SELF)
                            return@onEachWith

                        if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                            return@onEachWith

                        val allowSend = serviceMessageCategoriesController.isEnabled(
                            record.ownerUserId,
                            record.peerUserId,
                            ServiceMessageCategory.PET
                        )

                        if (!allowSend)
                            return@onEachWith

                        serviceMessagesController
                            .sendPetSetName(accountId, peerUserId, record.name)
                    }

                    is PluginEvent.StreakPetDeletedEvent -> {
                        if (by != PluginEvent.StreakPetDeletedEvent.By.SELF)
                            return@onEachWith

                        if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                            return@onEachWith

                        val allowSend = serviceMessageCategoriesController.isEnabled(
                            record.ownerUserId,
                            record.peerUserId,
                            ServiceMessageCategory.PET
                        )

                        if (!allowSend)
                            return@onEachWith

                        serviceMessagesController
                            .sendPetDeleted(accountId, peerUserId)
                    }

                    else -> {}
                }
            }
            .launchIn(backgroundScope)
    }

    fun enqueueTask(name: String, callback: suspend () -> Unit) =
        taskQueue.enqueueTask(name, callback)

    fun enqueueAccountInitializationTasks(accountId: Int, reason: String) {
        AccountTaskExecutor.enqueue(
            accountId,
            "prune invalid streaks and pets for account $accountId ($reason)"
        ) {
            streaksController.pruneInvalid(accountId)
            streakPetsController.pruneInvalid(accountId)
        }

        AccountTaskExecutor.enqueue(
            accountId,
            "patch user's emoji statuses for account $accountId ($reason)"
        ) {
            val accounts = userConfigAuthorizedIds
                .associateBy { UserConfig.getInstance(it).clientUserId }

            val perAccountPeerIds = hashMapOf<Int, ArrayList<Long>>()

            for (streak in streaksController.getAllVisible()) {
                val accountId = accounts[streak.ownerUserId] ?: continue

                perAccountPeerIds
                    .computeIfAbsent(accountId) { arrayListOf() }
                    .add(streak.peerUserId)
            }

            perAccountPeerIds.forEach(UserPatcher::patchUsers)

            runOnMainThread { streakEmojiRegistry.refreshDialogCells() }
        }

        AccountTaskExecutor.enqueue(
            accountId,
            "check for updates and update UI for account $accountId ($reason)"
        ) {
            withContext(RateLimitContext { throttlingClock ->
                if (throttlingClock == null) {
                    CheckNotificationHelper.cancelRateLimitNotification()
                    return@RateLimitContext
                }

                val (elapsedSec, totalSec) = throttlingClock

                CheckNotificationHelper.showRateLimitCountdown(
                    remainingMs = (totalSec - elapsedSec) * 1000L,
                    totalMs = totalSec * 1000L,
                )
            }) {
                streaksController.checkAllForUpdates(
                    accountId,
                    CheckNotificationHelper::updateCheckProgress
                )

                CheckNotificationHelper.cancelCheckProgress()

                streakPetsController.checkAllForUpdates(accountId)
            }

            streaksController.flushCurrentChatPopup()
        }
    }

    private fun onInject() {
        PluginBadges.add()

        BadgesCompat.init()

        CheckNotificationHelper.createChannel()

        subscribeToEvents()

        runOnMainThread {
            NotificationCenter.getGlobalInstance()
                .addObserver(themeObserver, NotificationCenter.didSetNewTheme)
        }

        taskQueue.startWorker(backgroundScope)

        ChatContextMenuActions(this).register()
        SettingsMenuActions(this).register()

        Logger.info("Injected!")
    }

    private fun onFinalizeInject() {
        runBlocking {
            streakEmojiPacksController.init()
            streaksController.loadCaches()
        }

        Logger.tryOrFatal(
            "hook methods",
            ::hookMethods
        )

        enqueueAccountInitializationTasks(UserConfig.selectedAccount, "plugin inject")

        backgroundScope.launch {
            try {
                Logger.info("Starting automating database backup loop...")
                databaseBackupManager.runAutoBackupLoop()
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Logger.fatal("Automatic database backup loop failed", e)
            }
        }

        Logger.info("Inject finalized!")
    }

    @Blocking
    private fun onEject() {
        Logger.info("onEject called!")

        Logger.tryOrFatal(
            "remove plugin badges",
            PluginBadges::remove
        )

        // hooks
        hooks.forEach {
            Logger.tryOrFatal(
                "unhook method ${it.hookedMethod}",
                it::unhook
            )
        }
        hooks.clear()

        // bg
        taskQueue.stopWorker()
        backgroundScope.cancel()

        Logger.info("Waiting for background coroutines to finish..")
        runBlocking { backgroundScope.coroutineContext.job.join() }
        Logger.info("Background coroutines finished!")

        // ui
        runOnMainThread {
            NotificationCenter.getGlobalInstance()
                .removeObserver(themeObserver, NotificationCenter.didSetNewTheme)

            petUiManager.dismissAll()
            streakEmojiRegistry.restoreAll()
        }

        // database (will be closed after notifying all subscribers except logger)
        EjectNotifier.subscribe(999) {
            Logger.info("Waiting for ref counter to be zero..")
            runBlocking { RefCounter.wait() }
            Logger.info("No more refs from other threads!")

            Logger.tryOrFatal("checkpoint database WAL") {
                runBlocking {
                    db.useWriterConnection { transactor ->
                        transactor.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
                    }
                }
            }

            db.close()
        }

        // subscribers
        EjectNotifier.fire()
    }

    fun enqueueRebuildForPeer(
        accountId: Int,
        peerUserId: Long,
        @UiThread onComplete: (() -> Unit)? = null,
    ) {
        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

        if (peerUser == null || !isPeerValid(peerUser)) {
            BulletinHelper.show(Strings.status_info_chat_private_users_only())
            return
        }

        AccountTaskExecutor.enqueue(
            accountId,
            "rebuild streak for $accountId:$peerUserId"
        ) {
            streaksController.rebuild(accountId, peerUser)

            if (onComplete != null)
                runOnMainThread(onComplete)
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
                        Logger.tryOrFatal("run $method before-call hook") { callback(param) }
                    }
                }
            )
        }

        fun after(method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
            add(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logger.tryOrFatal("run $method after-call hook") { callback(param) }
                    }
                }
            )
        }

        val bundles = listOf(
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
            UpdatesHookBundle(),
        )

        bundles.forEach { it.inject(::before, ::after) }
    }
}
