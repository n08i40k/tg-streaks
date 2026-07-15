package ru.n08i40k.streaks

import android.graphics.Color
import android.webkit.ValueCallback
import androidx.room.Room
import androidx.room.RoomDatabase
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.controller.PluginRelationController
import ru.n08i40k.streaks.controller.ServiceMessageCategoriesController
import ru.n08i40k.streaks.controller.ServiceMessagesController
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.controller.TimeZonesController
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.data.StreakPetLevel
import ru.n08i40k.streaks.database.DatabaseBackupManager
import ru.n08i40k.streaks.database.MIGRATION_10_11
import ru.n08i40k.streaks.database.MIGRATION_1_2
import ru.n08i40k.streaks.database.MIGRATION_2_3
import ru.n08i40k.streaks.database.MIGRATION_3_5
import ru.n08i40k.streaks.database.MIGRATION_5_6
import ru.n08i40k.streaks.database.MIGRATION_6_7
import ru.n08i40k.streaks.database.MIGRATION_7_8
import ru.n08i40k.streaks.database.MIGRATION_8_9
import ru.n08i40k.streaks.database.MIGRATION_9_10
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.collectOnUIThread
import ru.n08i40k.streaks.extension.collectWith
import ru.n08i40k.streaks.extension.collectWithOnUIThread
import ru.n08i40k.streaks.extension.diff
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.now
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
import ru.n08i40k.streaks.registry.StreakLevelRegistry
import ru.n08i40k.streaks.registry.StreakPetLevelRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.StreakPetUiManager
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BadgesCompat
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.CheckNotificationHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RateLimitContext
import ru.n08i40k.streaks.util.StreakAlertNotificationHelper
import ru.n08i40k.streaks.util.TaskQueue
import ru.n08i40k.streaks.util.UserPatcher
import java.lang.reflect.Member
import kotlin.time.Instant

typealias LogReceiver = ValueCallback<String>

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null
        private var VERSION: String? = null

        fun isInjected(): Boolean = INSTANCE != null

        // should not be called from python
        @JvmStatic
        fun getInstance(): Plugin = INSTANCE!!

        @JvmStatic
        fun getBuildDate(): String = Instant
            .fromEpochMilliseconds(BuildConfig.BUILD_TIME.toLong())
            .toString()

        @JvmStatic
        fun getVersion(): String? = VERSION

        @JvmStatic
        fun inject(
            version: String,
            logReceiver: LogReceiver,
            resourcesRootPath: String,
        ) {
            if (INSTANCE != null)
                return

            VERSION = version

            Logger.setReceiver(logReceiver)

            i18n4k = I18n4kConfigDefault().apply {
                locale = createLocale(
                    LocaleController
                        .getInstance()
                        .resolveLanguageCode()
                )
            }
            MessageFormatterDefault.registerMessageValueFormatters(MessagePluralFormatter)

            try {
                INSTANCE = Plugin(ResourcesProvider(resourcesRootPath))
            } catch (e: Throwable) {
                logReceiver.onReceiveValue("Failed to create plugin instance")
                logReceiver.onReceiveValue(e.toString())
                logReceiver.onReceiveValue(e.stackTrace.joinToString("\n"))
                return
            }

            Logger.tryOrFatal(
                "Failed to inject plugin",
                INSTANCE!!::onInject
            )
        }

        @JvmStatic
        fun invokeChatContextMenuCallback(key: String, id: Long) = with(INSTANCE!!) {
            chatContextMenuCallbackRegistry.get(key).accept(id)
        }

        @JvmStatic
        fun invokeSettingsActionCallback(key: String) = with(INSTANCE!!) {
            settingsActionCallbackRegistry.get(key).run()
        }

        @JvmStatic
        fun registerStreakLevel(
            length: Int,
            color: Color,
            documentId: Long,
            popupResourceName: String,
        ) = with(INSTANCE!!) {
            streakLevelRegistry.register(StreakLevel(length, color, documentId, popupResourceName))
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
        ) = with(INSTANCE!!) {
            streakPetLevelRegistry.register(
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
        fun finalizeInject() = with(INSTANCE!!) {
            onFinalizeInject()
        }

        @JvmStatic
        fun setPetFabSizeDp(sizeDp: Int) = with(INSTANCE!!) {
            petUiManager.setFabSizeDp(sizeDp)
        }

        @JvmStatic
        fun eject() = AndroidUtilities.runOnUIThread {
            Logger.tryOrFatal("Failed to eject plugin") {
                INSTANCE?.onEject()
            }

            INSTANCE = null
        }
    }

    val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            Logger.fatal("An unknown error occurred in background coroutine scope", exception)
        })

    // database
    private val db: PluginDatabase = Room.databaseBuilder(
        ApplicationLoader.applicationContext,
        PluginDatabase::class.java,
        "tg-streaks"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_5, MIGRATION_5_6)
        .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
        .addMigrations(MIGRATION_10_11)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

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

    // controllers
    val serviceMessagesController = ServiceMessagesController()
    val streaksController: StreaksController
    val streakPetsController: StreakPetsController
    val timeZonesController: TimeZonesController
    val pluginRelationController: PluginRelationController
    val serviceMessageCategoriesController: ServiceMessageCategoriesController
    val petUiManager: StreakPetUiManager

    // registries
    val streakLevelRegistry: StreakLevelRegistry = StreakLevelRegistry()
    val streakPetLevelRegistry: StreakPetLevelRegistry = StreakPetLevelRegistry()

    constructor(resourcesProvider: ResourcesProvider) {
        try {
            this.resourcesProvider = resourcesProvider
            this.alertNotificationHelper = StreakAlertNotificationHelper()

            // background work
            this.taskQueue = TaskQueue()

            // database
            this.databaseBackupManager = DatabaseBackupManager(this.db, Logger::info)

            // controllers
            this.timeZonesController = TimeZonesController(this.db)
            this.streaksController =
                StreaksController(this.db, this.timeZonesController, this.resourcesProvider)
            this.streakPetsController =
                StreakPetsController(this.db, this.streaksController, this.timeZonesController)
            this.pluginRelationController = PluginRelationController(this.db)
            this.serviceMessageCategoriesController = ServiceMessageCategoriesController(this.db)

            this.petUiManager = StreakPetUiManager()
        } catch (e: Throwable) {
            this.db.close()
            throw e
        }
    }

    @OptIn(FlowPreview::class)
    private fun subscribeToEvents() {
        // streak ui patches/transitions
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.StreakEvent>()
                .collectWithOnUIThread {
                    streakEmojiRegistry.refreshByPeerUserId(peerUserId)

                    when (this) {
                        is PluginEvent.StreakCreatedEvent -> {
                            if (!record.isVisible)
                                return@collectWithOnUIThread

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
                                return@collectWithOnUIThread

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
        }

        // streak dependents
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.StreakDeletedEvent>()
                .collectWith {
                    streakPetsController.delete(accountId, record.peerUserId, timestamp, true)
                }
        }

        // debounced dialog cells refresh
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.StreakEvent>()
                .debounce(100)
                .collectOnUIThread { streakEmojiRegistry.refreshDialogCells() }
        }

        // pre-death notification
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.StreakDeathWarningEvent>()
                .collectWith {
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
        }

        // streak pet fab
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.StreakPetEvent>()
                .collectWithOnUIThread {
                    petUiManager.refreshFabForOpenChat()
                    petUiManager.refreshOpenedDialog(accountId, peerUserId)
                }
        }

        // service messages
        backgroundScope.launch {
            EventBus.stream
                .filterIsInstance<PluginEvent.PeerEvent>()
                .collectWith {
                    when (this) {
                        is PluginEvent.StreakGrowUpEvent -> {
                            if (LocalDate.now(record.timeZone)
                                    .diff(timestamp.toLocalDate(record.timeZone)) > 2
                            )
                                return@collectWith

                            val allowSend = serviceMessageCategoriesController.isEnabled(
                                record.ownerUserId,
                                record.peerUserId,
                                ServiceMessageCategory.LEVEL_UP
                            )

                            if (!allowSend)
                                return@collectWith

                            val targetLevelLength = targetRecord.level.length

                            if (targetLevelLength == targetRecord.length
                                && targetLevelLength == streakLevelRegistry.getFirstVisible().length
                            ) {
                                serviceMessagesController
                                    .sendCreation(accountId, peerUserId)
                                return@collectWith
                            }

                            if (targetRecord.level <= sourceRecord.level)
                                return@collectWith

                            serviceMessagesController
                                .sendUpgrade(accountId, peerUserId, targetRecord.level.length)
                        }

                        is PluginEvent.StreakLostEvent -> {
                            if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                                return@collectWith

                            val allowSend = serviceMessageCategoriesController.isEnabled(
                                record.ownerUserId,
                                record.peerUserId,
                                ServiceMessageCategory.LIFECYCLE
                            )

                            if (!allowSend)
                                return@collectWith

                            serviceMessagesController
                                .sendDeath(accountId, peerUserId)
                        }

                        is PluginEvent.StreakRestoredEvent -> {
                            if (byPeer)
                                return@collectWith

                            if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                                return@collectWith

                            val allowSend = serviceMessageCategoriesController.isEnabled(
                                record.ownerUserId,
                                record.peerUserId,
                                ServiceMessageCategory.LIFECYCLE
                            )

                            if (!allowSend)
                                return@collectWith

                            serviceMessagesController
                                .sendRestore(accountId, peerUserId)
                        }

                        is PluginEvent.StreakPetRenamedEvent -> {
                            if (by != PluginEvent.StreakPetRenamedEvent.By.SELF)
                                return@collectWith

                            if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                                return@collectWith

                            val allowSend = serviceMessageCategoriesController.isEnabled(
                                record.ownerUserId,
                                record.peerUserId,
                                ServiceMessageCategory.PET
                            )

                            if (!allowSend)
                                return@collectWith

                            serviceMessagesController
                                .sendPetSetName(accountId, peerUserId, record.name)
                        }

                        is PluginEvent.StreakPetDeletedEvent -> {
                            if (by != PluginEvent.StreakPetDeletedEvent.By.SELF)
                                return@collectWith

                            if (timestamp.toLocalDate(record.timeZone) != LocalDate.now(record.timeZone))
                                return@collectWith

                            val allowSend = serviceMessageCategoriesController.isEnabled(
                                record.ownerUserId,
                                record.peerUserId,
                                ServiceMessageCategory.PET
                            )

                            if (!allowSend)
                                return@collectWith

                            serviceMessagesController
                                .sendPetDeleted(accountId, peerUserId)
                        }

                        else -> {}
                    }
                }
        }
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

            AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
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

        taskQueue.startWorker(backgroundScope)

        ChatContextMenuActions(this).register()
        SettingsMenuActions(this).register()

        Logger.info("Injected!")
    }

    private fun onFinalizeInject() {
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

    private fun onEject() {
        Logger.tryOrFatal(
            "remove plugin badges",
            PluginBadges::remove
        )

        taskQueue.stopWorker()

        backgroundScope.cancel()

        petUiManager.dismissAll()

        hooks.forEach {
            Logger.tryOrFatal(
                "unhook method ${it.hookedMethod}",
                it::unhook
            )
        }
        hooks.clear()

        streakEmojiRegistry.restoreAll()

        chatContextMenuCallbackRegistry.clear()
        settingsActionCallbackRegistry.clear()

        db.close()

        EjectNotifier.fire()
    }

    fun enqueueRebuildForPeer(
        accountId: Int,
        peerUserId: Long,
        onComplete: (() -> Unit)? = null,
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
            UpdatesHookBundle()
        )

        bundles.forEach { it.inject(::before, ::after) }
    }
}
