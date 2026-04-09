@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
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
import org.telegram.messenger.BaseController
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Cells.UserCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.constants.ChatContextMenuButton
import ru.n08i40k.streaks.constants.ServiceMessage
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
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.registry.LockableActionRegistry
import ru.n08i40k.streaks.registry.LockableCallbackRegistry
import ru.n08i40k.streaks.registry.StreakEmojiRegistry
import ru.n08i40k.streaks.registry.StreakLevelRegistry
import ru.n08i40k.streaks.registry.StreakPetLevelRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.StreakPetDialog
import ru.n08i40k.streaks.ui.StreakPetFabDialog
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RuntimeGuard
import ru.n08i40k.streaks.util.TaskQueue
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.WidthCache
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.isClientVersionBelow
import java.lang.ref.WeakReference
import java.lang.reflect.Member
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

typealias LogReceiver = ValueCallback<String>
typealias TranslationResolver = java.util.function.Function<String, String?>

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null
        private const val DEFAULT_PET_FAB_SIZE_DP = 80
        private const val PET_FAB_OPEN_DELAY_MS = 220L

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
            INSTANCE?.applyPetFabSizeDp(sizeDp)
        }

        @JvmStatic
        fun eject() {
            // do not run on threads that may be destructed
            AndroidUtilities.runOnUIThread {
                try {
                    INSTANCE?.let {
                        it.onEject()
                        BulletinHelper.show(null, "Streaks plugin has been ejected!")
                    }

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
    val streakEmojiRegistry = StreakEmojiRegistry()

    // controllers
    private val serviceMessagesController = ServiceMessagesController()
    val streaksController: StreaksController
    val streakPetsController: StreakPetsController

    // registries
    val streakLevelRegistry: StreakLevelRegistry = StreakLevelRegistry()
    val streakPetLevelRegistry: StreakPetLevelRegistry = StreakPetLevelRegistry()

    private var openedPetDialog: StreakPetDialog? = null
    private var petFabDialog: StreakPetFabDialog? = null
    private var petFabEnabled: Boolean = true
    private var petFabSizeDp: Int = DEFAULT_PET_FAB_SIZE_DP
    private var pendingPetFabRefresh: Runnable? = null

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

        // database
        this.db = Room.databaseBuilder(
            ApplicationLoader.applicationContext,
            PluginDatabase::class.java,
            "tg-streaks"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_5, MIGRATION_5_6)
            .build()

        this.databaseBackupManager = DatabaseBackupManager(this.db, this.logger::info)

        // controllers
        this.streaksController = StreaksController(this.db, this.logger, this.resourcesProvider)
        this.streakPetsController =
            StreakPetsController(this.logger, this.db, this.streaksController)
    }

    fun enqueueTask(name: String, callback: suspend () -> Unit) =
        taskQueue.enqueueTask(name, callback)

    private fun enqueueAccountInitializationTasks(accountId: Int, reason: String) {
        enqueueTask("prune invalid streaks and pets for account $accountId ($reason)") {
            streaksController.pruneInvalid(accountId)
            streakPetsController.pruneInvalid(accountId)
        }

        enqueueTask("patch user's emoji statuses for account $accountId ($reason)") {
            streaksController.patchUsers(accountId)
        }

        enqueueTask("check for updates and update UI for account $accountId ($reason)") {
            syncPeersUi(streaksController.checkAllForUpdates(accountId))

            streakPetsController.checkAllForUpdates(accountId)
            streaksController.flushCurrentChatPopup()
        }
    }

    private fun enqueueSelectedAccountInitializationTasks(reason: String) {
        enqueueAccountInitializationTasks(UserConfig.selectedAccount, reason)
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

        enqueueSelectedAccountInitializationTasks("plugin inject")

        backgroundScope.launch {
            try {
                databaseBackupManager.runAutoBackupLoop()
            } catch (_: CancellationException) {
                // Suppress error
            } catch (e: Throwable) {
                logger.fatal("Automatic database backup loop failed", e)
            }
        }

        logger.info("Inject finalized!")
    }

    private fun onEject() {
        logger.setFatalSuppression(true)

        if (taskQueue.isWorkerRunning)
            taskQueue.stopWorker()

        uiScope.cancel()
        backgroundScope.cancel()

        openedPetDialog?.dismiss()
        clearTrackedPetDialog()
        dismissPetFab()

        try {
            hooks.forEach { it.unhook() }
            hooks.clear()
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

    // pet fab methods

    private fun dismissPetFab() {
        pendingPetFabRefresh?.let(AndroidUtilities::cancelRunOnUIThread)
        pendingPetFabRefresh = null
        petFabDialog?.dismiss()
        petFabDialog = null
    }

    private fun schedulePetFabRefreshForOpenChat(delayMs: Long = PET_FAB_OPEN_DELAY_MS) {
        pendingPetFabRefresh?.let(AndroidUtilities::cancelRunOnUIThread)

        val runnable = Runnable {
            pendingPetFabRefresh = null
            refreshPetFabForOpenChat()
        }

        pendingPetFabRefresh = runnable
        AndroidUtilities.runOnUIThread(runnable, delayMs)
    }

    private fun applyPetFabSizeDp(sizeDp: Int) {
        if (petFabSizeDp == sizeDp)
            return

        petFabSizeDp = sizeDp

        AndroidUtilities.runOnUIThread {
            petFabDialog?.updateSizeDp(sizeDp)
            petFabDialog?.configureWindow()
        }
    }

    private fun openPetDialog(accountId: Int, peerUserId: Long) {
        uiScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            if (uiState == null) {
                bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_PET_FOR_CHAT)
                return@launch
            }

            AndroidUtilities.runOnUIThread {
                val fragment = LaunchActivity.getSafeLastFragment()
                if (fragment == null) {
                    bulletinHelper.showTranslated(TranslationKey.ERR_CANNOT_OPEN_CHAT_CONTEXT)
                    return@runOnUIThread
                }

                if (
                    openedPetDialog?.isShowing == true
                    && openedPetDialog?.matches(accountId, peerUserId) == true
                ) {
                    openedPetDialog?.updateState(uiState)
                    return@runOnUIThread
                }

                openedPetDialog?.dismiss()
                clearTrackedPetDialog()
                dismissPetFab()

                val dialog = StreakPetDialog(
                    fragment,
                    accountId,
                    peerUserId,
                    uiState,
                    resourcesProvider,
                    translator,
                    onRenameRequested = { newName ->
                        enqueueTask("rename pet for $accountId:$peerUserId") {
                            if (!streakPetsController.rename(accountId, peerUserId, newName)) {
                                return@enqueueTask
                            }

                            serviceMessagesController.sendPetSetName(
                                accountId,
                                peerUserId,
                                newName
                            )
                            refreshOpenedPetDialog(accountId, peerUserId)
                        }
                    },
                    onDismissed = {
                        refreshPetFabForOpenChat()
                    }
                )

                trackPetDialog(dialog)
                fragment.showDialog(dialog)
            }
        }
    }

    private fun refreshPetFabForOpenChat() {
        if (!petFabEnabled) {
            AndroidUtilities.runOnUIThread { dismissPetFab() }
            return
        }

        val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity ?: run {
            AndroidUtilities.runOnUIThread { dismissPetFab() }
            return
        }

        val accountId = UserConfig.selectedAccount
        val peerUserId = chatActivity.dialogId

        if (peerUserId <= 0L) {
            AndroidUtilities.runOnUIThread { dismissPetFab() }
            return
        }

        uiScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            AndroidUtilities.runOnUIThread {
                if (!petFabEnabled) {
                    dismissPetFab()
                    return@runOnUIThread
                }

                val currentChat = LaunchActivity.getSafeLastFragment() as? ChatActivity
                if (currentChat == null || currentChat.dialogId != peerUserId) {
                    dismissPetFab()
                    return@runOnUIThread
                }

                if (uiState == null) {
                    dismissPetFab()
                    return@runOnUIThread
                }

                if (
                    petFabDialog?.isShowing == true
                    && petFabDialog?.matches(accountId, peerUserId) == true
                ) {
                    petFabDialog?.updateState(uiState)
                    return@runOnUIThread
                }

                dismissPetFab()

                val context =
                    currentChat.parentActivity ?: currentChat.context ?: return@runOnUIThread
                val dialog = StreakPetFabDialog(
                    context,
                    accountId,
                    peerUserId,
                    uiState,
                    resourcesProvider,
                    petFabSizeDp,
                ) {
                    dismissPetFab()
                    openPetDialog(accountId, peerUserId)
                }
                dialog.show()
                dialog.configureWindow()
                petFabDialog = dialog
            }
        }
    }

    private fun clearTrackedPetDialog(dialog: StreakPetDialog? = null) {
        if (dialog != null && openedPetDialog !== dialog) {
            return
        }

        openedPetDialog = null
    }

    private fun trackPetDialog(dialog: StreakPetDialog) {
        openedPetDialog = dialog
        dialog.setOnDismissListener {
            clearTrackedPetDialog(dialog)
        }
    }

    private fun refreshOpenedPetDialog(accountId: Int, peerUserId: Long) {
        if (openedPetDialog?.matches(accountId, peerUserId) != true) {
            return
        }

        uiScope.launch {
            val refreshedState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            AndroidUtilities.runOnUIThread {
                val dialog = openedPetDialog ?: return@runOnUIThread
                if (!dialog.matches(accountId, peerUserId) || !dialog.isShowing) {
                    clearTrackedPetDialog(dialog)
                    return@runOnUIThread
                }

                if (refreshedState == null) {
                    dialog.dismiss()
                    clearTrackedPetDialog(dialog)
                    return@runOnUIThread
                }

                dialog.updateState(refreshedState)
            }
        }
    }

    private suspend fun syncPeerUi(accountId: Int, peerUserId: Long) {
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

    private data class PendingIncomingUpdate(
        val peerUserId: Long,
        val at: LocalDate,
        val out: Boolean,
        val messageId: Int,
        val message: String?
    )

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
                bulletinHelper.showTranslated(TranslationKey.INFO_DEBUG_PRIVATE_USER_ONLY)
                return null
            }

            return peerUser
        }

        fun validatePrivatePeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (!isPeerValid(peerUser)) {
                bulletinHelper.showTranslated(TranslationKey.INFO_PRIVATE_USER_ONLY)
                return null
            }

            return peerUser
        }

        add(ChatContextMenuButton.REBUILD) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validatePrivatePeer(accountId, peerUserId) ?: return@add

            enqueueTask("rebuild streak for $accountId:$peerUserId") {
                streaksController.rebuild(
                    accountId,
                    peerUser
                ) { progress -> progress.showBulletin() }

                streaksController.syncUserState(accountId, peerUserId)

                AndroidUtilities.runOnUIThread {
                    streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                    streakEmojiRegistry.refreshDialogCells()
                }

                val rebuiltStreak = streaksController.get(accountId, peerUserId)

                if (rebuiltStreak != null) {
                    bulletinHelper.showTranslated(
                        TranslationKey.FORCE_CHECK_SUMMARY_CHAT,
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
            }

            logger.info("[Context Menu] Rebuild clicked on $peerUserId")
        }

        add(ChatContextMenuButton.REBUILD_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validatePrivatePeer(accountId, peerUserId) ?: return@add

            if (streakPetsController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.INFO_FORCE_CHECK_ALREADY_RUNNING)
                return@add
            }

            enqueueTask("rebuild streak-pet for $accountId:$peerUserId") {
                val streakPet = streakPetsController.get(accountId, peerUserId)

                if (streakPet == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_PET_FOR_CHAT)
                    return@enqueueTask
                }

                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@enqueueTask
                }

                streakPetsController.rebuild(accountId, peerUser) { progress ->
                    progress.showBulletin()
                }
            }

            logger.info("[Context Menu] Rebuild pet clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_PET_FAB) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            validatePrivatePeer(accountId, peerUserId) ?: return@add

            petFabEnabled = !petFabEnabled

            if (petFabEnabled) {
                refreshPetFabForOpenChat()
                bulletinHelper.showTranslated(
                    TranslationKey.OK_STREAK_PET_FAB_ENABLED,
                    "msg_reactions"
                )
            } else {
                AndroidUtilities.runOnUIThread { dismissPetFab() }
                bulletinHelper.showTranslated(
                    TranslationKey.OK_STREAK_PET_FAB_DISABLED,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Toggle pet fab clicked on $peerUserId; enabled=$petFabEnabled")
        }

        add(ChatContextMenuButton.CREATE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            validatePrivatePeer(accountId, peerUserId) ?: return@add

            enqueueTask("try to create streak-pet for $accountId:$peerUserId") {
                if (streakPetsController.get(accountId, peerUserId) != null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_PET_ALREADY_EXISTS_FOR_CHAT)
                    return@enqueueTask
                }

                AndroidUtilities.runOnUIThread {
                    val fragment = LaunchActivity.getSafeLastFragment()
                    if (fragment == null) {
                        bulletinHelper.showTranslated(TranslationKey.ERR_CANNOT_OPEN_CHAT_CONTEXT)
                        return@runOnUIThread
                    }

                    fragment.showDialog(
                        AlertDialog.Builder(fragment.context)
                            .setTitle(translator.translate(TranslationKey.DIALOG_CREATE_STREAK_PET_TITLE))
                            .setMessage(translator.translate(TranslationKey.DIALOG_CREATE_STREAK_PET_MESSAGE))
                            .setPositiveButton(
                                translator.translate(TranslationKey.DIALOG_CREATE_STREAK_PET_YES)
                            ) { _, _ ->
                                streaksController.setServiceMessagesEnabled(
                                    accountId,
                                    peerUserId,
                                    true
                                )
                                serviceMessagesController.sendPetInvite(accountId, peerUserId)
                            }
                            .setNegativeButton(
                                translator.translate(TranslationKey.DIALOG_CREATE_STREAK_PET_NO)
                            ) { _, _ ->
                                enqueueTask("create streak-pet for $accountId:$peerUserId") {
                                    when (streakPetsController.create(accountId, peerUserId)) {
                                        is StreakPetsController.CreateResult.Created -> {
                                            refreshPetFabForOpenChat()
                                            bulletinHelper.showTranslated(
                                                TranslationKey.OK_STREAK_PET_CREATED,
                                                "msg_reactions"
                                            )
                                        }

                                        is StreakPetsController.CreateResult.AlreadyExists -> {
                                            bulletinHelper.showTranslated(
                                                TranslationKey.INFO_STREAK_PET_ALREADY_EXISTS_FOR_CHAT
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
                bulletinHelper.showTranslated(TranslationKey.INFO_PRIVATE_USER_ONLY)
                return@add
            }

            val chatActivity = (LaunchActivity.getSafeLastFragment() as? ChatActivity)
                ?.takeIf { it.dialogId == peerUserId }

            if (chatActivity == null) {
                bulletinHelper.showTranslated(TranslationKey.ERR_CANNOT_OPEN_CHAT_CONTEXT)
                logger.info("[Context Menu] Go-to-streak-start failed: no chat context for $peerUserId")
                return@add
            }

            bulletinHelper.showTranslated(TranslationKey.INFO_SEARCHING_STREAK_START_MESSAGE)

            enqueueTask("go to streak start for $accountId:$peerUserId") {
                try {
                    val streak = streaksController.get(accountId, peerUserId)

                    if (streak == null) {
                        bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                        return@enqueueTask
                    }

                    val jumpTs = streak.createdAt.toEpochSecondSystem().toInt()
                    val messageId = streaksController.findStartMessageId(accountId, peerUserId)

                    AndroidUtilities.runOnUIThread {
                        try {
                            if (messageId != null && messageId > 0) {
                                try {
                                    chatActivity.scrollToMessageId(messageId, 0, true, 0, true, 0)
                                    bulletinHelper.showTranslated(
                                        TranslationKey.OK_JUMPED_TO_STREAK_START_MESSAGE
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
                                TranslationKey.INFO_EXACT_START_MESSAGE_NOT_FOUND
                            )
                        } catch (e: Throwable) {
                            logger.fatal(
                                "Go-to-streak-start failed for peer $peerUserId",
                                e
                            )
                            bulletinHelper.showTranslated(
                                TranslationKey.ERR_FAILED_JUMP_TO_STREAK_START
                            )
                        }
                    }
                } catch (e: Throwable) {
                    logger.fatal("Go-to-streak-start lookup failed for peer $peerUserId", e)
                    bulletinHelper.showTranslated(TranslationKey.ERR_FAILED_JUMP_TO_STREAK_START)
                }
            }

            logger.info("[Context Menu] Go-to-streak-start clicked on $peerUserId")
        }

        add(ChatContextMenuButton.TOGGLE_SERVICE_MESSAGES) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            validatePrivatePeer(accountId, peerUserId) ?: return@add

            val enabled = streaksController.toggleServiceMessages(accountId, peerUserId)

            bulletinHelper.showTranslated(
                if (enabled) {
                    TranslationKey.OK_UPGRADE_SERVICE_MESSAGES_ENABLED
                } else {
                    TranslationKey.OK_UPGRADE_SERVICE_MESSAGES_DISABLED
                },
                "msg_reactions"
            )

            logger.info("[Context Menu] Toggle service messages clicked on $peerUserId; enabled=$enabled")
        }

        add(ChatContextMenuButton.REVIVE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validatePrivatePeer(accountId, peerUserId) ?: return@add

            enqueueTask("revive streak for $accountId:$peerUserId") {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@enqueueTask
                }

                if (!streak.dead) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_NOT_ENDED_YET)
                    return@enqueueTask
                }

                if (!streak.canRevive) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                    return@enqueueTask
                }

                if (!streaksController.reviveNow(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                    return@enqueueTask
                }

                if (streaksController.patchUser(accountId, peerUser))
                    MessagesController.getInstance(accountId).putUser(peerUser, false, true)

                streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
                bulletinHelper.showTranslated(
                    TranslationKey.OK_STREAK_RESTORED,
                    "msg_reactions"
                )
            }
        }

        add(ChatContextMenuButton.DEBUG_CREATE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("create debug streak for $accountId:$peerUserId") {
                streaksController.debugSetThreeDayStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_SET_3,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-create clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_UPGRADE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("upgrade debug streak for $accountId:$peerUserId") {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@enqueueTask
                }

                val nextLevel = streakLevelRegistry
                    .levels()
                    .firstOrNull { level -> level.length > streak.level.length }

                if (nextLevel == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_DEBUG_STREAK_ALREADY_MAX)
                    return@enqueueTask
                }

                val newLength =
                    streaksController.debugUpgradeStreak(accountId, peerUserId)
                        ?: return@enqueueTask
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_UPGRADED,
                    mapOf("days" to newLength.toString()),
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-upgrade clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_FREEZE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("freeze debug streak for $accountId:$peerUserId") {
                streaksController.debugFreezeStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_FROZEN,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-freeze clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_KILL) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("kill debug streak for $accountId:$peerUserId") {
                streaksController.debugMarkDead(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_MARKED_DEAD,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-kill clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("delete debug streak for $accountId:$peerUserId") {
                if (!streaksController.debugDeleteStreak(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@enqueueTask
                }

                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_DELETED,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-delete clicked on ${peerUser.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peerUser = validateDebugPeer(accountId, peerUserId) ?: return@add

            enqueueTask("delete debug streak for $accountId:$peerUserId") {
                if (!streakPetsController.delete(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_PET_FOR_CHAT)
                    return@enqueueTask
                }

                AndroidUtilities.runOnUIThread { dismissPetFab() }
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_PET_DELETED,
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
                bulletinHelper.showTranslated(TranslationKey.INFO_FORCE_CHECK_ALREADY_RUNNING)
                return@add
            }

            bulletinHelper.showTranslated(
                TranslationKey.INFO_FORCE_CHECK_STARTED_ALL,
                "msg_retry"
            )

            enqueueTask("rebuild all streaks for $accountId") {
                try {
                    val result =
                        streaksController.rebuildAll(accountId) { index, total, _, progress ->
                            bulletinHelper.showTranslated(
                                TranslationKey.FORCE_CHECK_DAY_PROGRESS_ALL_SIMPLE,
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
                        TranslationKey.FORCE_CHECK_SUMMARY_ALL_SIMPLE,
                        mapOf("checked" to result.totalChats.toString()),
                        "msg_retry"
                    )
                } catch (e: Throwable) {
                    logger.fatal("Failed to rebuild all private chats for account $accountId", e)
                    bulletinHelper.showTranslated(TranslationKey.ERR_FORCE_CHECK_FAILED_LOGS)
                }
            }
        }

        add(SettingsActionButton.EXPORT_BACKUP_NOW) {
            enqueueTask("export database backup") {
                try {
                    val backup = databaseBackupManager.exportNow()
                    bulletinHelper.showTranslated(
                        TranslationKey.OK_BACKUP_EXPORTED,
                        mapOf("name" to backup.name),
                        "msg_save"
                    )
                } catch (e: Throwable) {
                    logger.fatal("Failed to export database backup", e)
                    bulletinHelper.showTranslated(TranslationKey.ERR_BACKUP_EXPORT_FAILED)
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

        // Чат в списке, нужно ещё увеличить bounds по x, иначе текста не будет
        after(
            DialogCell::class.java.getConstructor(
                DialogsActivity::class.java,
                Context::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java,
                Theme.ResourcesProvider::class.java
            )
        )
        { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                0,
                true
            )
        }

        // Конструктор чата в списке не имеет его в качестве аргумента, он задаётся после
        after(
            DialogCell::class.java.getDeclaredMethod(
                "buildLayout",
            )
        ) { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            val currentDialogId =
                getFieldValue<Long>(thisClass, thisObject, "currentDialogId")!!

            getFieldValue<StreakEmoji>(
                thisClass,
                thisObject,
                "emojiStatus"
            )?.setPeerUserId(currentDialogId)
        }

        // Фикс отрисовки текста в местах, где размер view ограничен по x.
        // Например, в списке чатов, где у SwapAnimatedEmojiDrawable есть обёртка в виде View,
        // который жёстко ограничен по x.
        if (!isClientVersionBelow("12.2.6")) {
            after(
                DialogCell::class.java.getDeclaredMethod(
                    "onLayout",
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                )
            ) { param ->
                val thisObject = param.thisObject as DialogCell
                val thisClass = DialogCell::class.java

                val emojiStatusView =
                    getFieldValue<View>(thisClass, thisObject, "emojiStatusView")!!
                val emojiStatus =
                    getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                        thisClass,
                        thisObject,
                        "emojiStatus"
                    )

                val height = AndroidUtilities.dp(22f)
                emojiStatusView.layout(
                    0,
                    0,
                    maxOf(height * 4, emojiStatus?.intrinsicWidth ?: 0),
                    height
                )
            }
        }

        // Сообщение в группе
        after(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageObjectInternal",
                MessageObject::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ChatMessageCell
            val thisClass = ChatMessageCell::class.java

            val currentUser =
                getFieldValue<TLRPC.User>(thisClass, thisObject, "currentUser")
                    ?: return@after

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "currentNameStatusDrawable"),
                null,
                currentUser.id,
                true,
                null,
                StreakEmoji.Parent.MessageCell(WeakReference(thisObject))
            )
        }

        // каким блять хуем я не могу кастануть child в parent?
        // это кстати хук MessageObject для полной замены вида сервисных сообщений
        @Suppress("CAST_NEVER_SUCCEEDS")
        before(
            MessageObject::class.java.getDeclaredConstructor(
                Int::class.java,
                TLRPC.Message::class.java,
                MessageObject::class.java,
                java.util.AbstractMap::class.java,
                java.util.AbstractMap::class.java,
                androidx.collection.LongSparseArray::class.java,
                androidx.collection.LongSparseArray::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Long::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )

        ) { param ->
            val message = param.args[1] as? TLRPC.Message ?: return@before
            val currentAccount = param.args[0] as? Int ?: 0

            if (message.message == null)
                return@before

            val tryStreakCreate = streakCreate@{
                if (message.message != ServiceMessage.CREATE_TEXT)
                    return@streakCreate null

                TLRPC.TL_messageActionCustomAction().apply {
                    val messageText =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_CREATE_TEXT)
                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val tryStreakUpgrade = streakUpgrade@{
                val days = ServiceMessage.UPGRADE_REGEX
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: return@streakUpgrade null

                TLRPC.TL_messageActionCustomAction().apply {
                    val messageText =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_UPGRADE_TEXT)
                            .replace("{days}", days.toString())

                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val tryStreakDeath = streakDeath@{
                if (message.message != ServiceMessage.DEATH_TEXT)
                    return@streakDeath null

                TLRPC.TL_messageActionPrizeStars().apply {
                    boost_peer = message.peer_id
                    flags = 0
                    giveaway_msg_id = 0
                    stars = 0
                    transaction_id = ServiceMessage.DEATH_TEXT
                    unclaimed = false
                } as TLRPC.MessageAction
            }

            val tryStreakRestore = streakRestore@{
                if (message.message != ServiceMessage.RESTORE_TEXT)
                    return@streakRestore null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer =
                    peerId != null && fromId != null && peerId > 0 && fromId == peerId

                val messageText =
                    if (!byPeer) {
                        translator.translate(TranslationKey.SERVICE_MESSAGE_RESTORE_TEXT_SELF)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.SERVICE_MESSAGE_RESTORE_TEXT_PEER)
                            .replace("{name}", peerName)
                    }

                TLRPC.TL_messageActionCustomAction().apply {
                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val tryPetInvite = petInvite@{
                if (message.message != ServiceMessage.PET_INVITE_TEXT)
                    return@petInvite null

                if (message.out) {
                    TLRPC.TL_messageActionCustomAction().apply {
                        val messageText =
                            translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_TEXT_SELF)
                        (this as TLRPC.MessageAction).message = messageText
                    } as TLRPC.MessageAction
                } else {
                    TLRPC.TL_messageActionPrizeStars().apply {
                        boost_peer = message.peer_id
                        flags = 0
                        giveaway_msg_id = 0
                        stars = 0
                        transaction_id = ServiceMessage.PET_INVITE_TEXT
                        unclaimed = false
                    } as TLRPC.MessageAction
                }
            }

            val tryPetInviteAccepted = petInviteAccepted@{
                if (message.message != ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
                    return@petInviteAccepted null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer =
                    peerId != null && fromId != null && peerId > 0 && fromId == peerId

                val messageText =
                    if (!byPeer) {
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_ACCEPTED_TEXT_SELF)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_ACCEPTED_TEXT_PEER)
                            .replace("{name}", peerName)
                    }

                TLRPC.TL_messageActionCustomAction().apply {
                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val tryPetSetName = petSetName@{
                val name = ServiceMessage.PET_SET_NAME_REGEX
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@petSetName null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer =
                    peerId != null && fromId != null && peerId > 0 && fromId == peerId

                val messageText =
                    if (!byPeer) {
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_SET_NAME_TEXT_SELF)
                            .replace("{petName}", name)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_SET_NAME_TEXT_PEER)
                            .replace("{peerName}", peerName)
                            .replace("{petName}", name)
                    }

                TLRPC.TL_messageActionCustomAction().apply {
                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val action = tryStreakCreate()
                ?: tryStreakUpgrade()
                ?: tryStreakDeath()
                ?: tryStreakRestore()
                ?: tryPetInvite()
                ?: tryPetInviteAccepted()
                ?: tryPetSetName()
                ?: return@before

            param.args[1] = TLRPC.TL_messageService().apply {
                cloneFields(message as Object, this as Object, TLRPC.Message::class.java)

                (this as TLRPC.Message).action = action
                (this as TLRPC.Message).message = null
            }
        }

        // Callback для кнопки у сервисного сообщения основанного на gift
        @Suppress("CAST_NEVER_SUCCEEDS")
        before(
            ChatActionCell::class.java.getDeclaredMethod(
                "openStarsGiftTransaction",
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@before

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@before

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    enqueueTask("try to revive streak from notification") {
                        val accountId = UserConfig.selectedAccount
                        val peerUserId = messageObject.dialogId

                        val streak = streaksController.get(accountId, peerUserId)

                        if (streak == null) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                            return@enqueueTask
                        }

                        if (!streak.dead) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_NOT_ENDED_YET)
                            return@enqueueTask
                        }

                        if (!streak.canRevive) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                            return@enqueueTask
                        }

                        if (!streaksController.reviveNow(accountId, peerUserId)) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                            return@enqueueTask
                        }
                    }
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    enqueueTask("try to accept streak-pet invitation from notification") {
                        val accountId = UserConfig.selectedAccount
                        val peerUserId = messageObject.dialogId
                        streaksController.setServiceMessagesEnabled(accountId, peerUserId, true)
                        serviceMessagesController.sendPetInviteAccepted(
                            accountId,
                            peerUserId
                        )

                        when (streakPetsController.create(accountId, peerUserId)) {
                            is StreakPetsController.CreateResult.Created -> {
                                syncPeerUi(accountId, peerUserId)
                                refreshPetFabForOpenChat()
                                bulletinHelper.showTranslated(
                                    TranslationKey.OK_STREAK_PET_CREATED,
                                    "msg_reactions"
                                )
                            }

                            is StreakPetsController.CreateResult.AlreadyExists -> {
                                bulletinHelper.showTranslated(
                                    TranslationKey.INFO_STREAK_PET_ALREADY_EXISTS_FOR_CHAT
                                )
                            }
                        }
                    }
                }

                else -> return@before
            }

            param.result = null
        }

        // Текст у сервисных сообщений основанных на gift
        @Suppress("CAST_NEVER_SUCCEEDS")
        before(
            ChatActionCell::class.java.getDeclaredMethod(
                "createGiftPremiumLayouts",
                CharSequence::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                Boolean::class.java,
                CharSequence::class.java,
                Int::class.java,
                CharSequence::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@before

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@before

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    param.args[0] = translator.translate(TranslationKey.SERVICE_MESSAGE_DEATH_TITLE)
                    param.args[1] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_DEATH_SUBTITLE)
                    param.args[3] = translator.translate(TranslationKey.SERVICE_MESSAGE_DEATH_HINT)
                    param.args[5] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_DEATH_BUTTON)
                    param.args[9] = false
                    param.args[10] = true
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    param.args[0] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_TITLE)
                    param.args[1] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_SUBTITLE)
                    param.args[3] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_HINT)
                    param.args[5] =
                        translator.translate(TranslationKey.SERVICE_MESSAGE_PET_INVITE_BUTTON)
                    param.args[9] = false
                    param.args[10] = true
                }
            }
        }

        // "Новый" ui у gift
        @Suppress("CAST_NEVER_SUCCEEDS")
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "isNewStyleButtonLayout",
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@after

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
                return@after

            param.result = true
        }

        // Фикс размеров gift
        @Suppress("CAST_NEVER_SUCCEEDS")
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "getImageSize",
                MessageObject::class.java
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@after

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
                return@after

            param.result = -AndroidUtilities.dp(19.5f)
        }

        // Удаление анимации у gift
        @Suppress("CAST_NEVER_SUCCEEDS")
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "setMessageObject",
                MessageObject::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val messageObject = param.args[0] as? MessageObject ?: return@after

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
                return@after

            val thisObject = param.thisObject as ChatActionCell
            val thisClass = ChatActionCell::class.java

            val imageReceiver =
                getFieldValue<ImageReceiver>(thisClass, thisObject, "imageReceiver")!!
            imageReceiver.setAllowStartLottieAnimation(false)
            imageReceiver.setDelegate(null)
            imageReceiver.setImageBitmap(null as Bitmap?)
            imageReceiver.clearImage()
            imageReceiver.clearDecorators()
            imageReceiver.setVisible(false, true)
        }

        // Исправление размера сообщения в чате
        after(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageContent",
                MessageObject::class.java,
                MessageObject.GroupedMessages::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ChatMessageCell
            val thisClass = ChatMessageCell::class.java

            val streakEmoji = getFieldValue<StreakEmoji>(
                thisClass,
                thisObject,
                "currentNameStatusDrawable"
            ) ?: return@after

            chatMessageCellWidthCache.changeIfNeeded(thisObject, streakEmoji.getAdditionalWidth())
        }

        // Пользователь в списке участников группы
        after(
            UserCell::class.java.getDeclaredMethod(
                "update",
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as UserCell
            val thisClass = UserCell::class.java
            val nameTextView =
                getFieldValue<SimpleTextView>(thisClass, thisObject, "nameTextView")!!
            val rightDrawableField = getField(SimpleTextView::class.java, "rightDrawable")
            val rightDrawable2Field = getField(SimpleTextView::class.java, "rightDrawable2")

            val dialogId = getFieldValue<Long>(thisClass, thisObject, "dialogId")!!

            if (dialogId < 0)
                return@after

            val oldEmojiStatus =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus"
                )
            val oldEmojiStatus2 =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus2"
                )

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                dialogId,
                nameTextView = nameTextView
            )

            val newEmojiStatus =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus"
                )

            val newEmojiStatus2 =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus2"
                )

            val currentRightDrawable = rightDrawableField.get(nameTextView)
            val currentRightDrawable2 = rightDrawable2Field.get(nameTextView)

            if (currentRightDrawable === oldEmojiStatus)
                nameTextView.rightDrawable = newEmojiStatus
            else if (currentRightDrawable === oldEmojiStatus2)
                nameTextView.rightDrawable = newEmojiStatus2

            if (currentRightDrawable2 === oldEmojiStatus)
                nameTextView.rightDrawable2 = newEmojiStatus
            else if (currentRightDrawable2 === oldEmojiStatus2)
                nameTextView.rightDrawable2 = newEmojiStatus2
        }

        // Профиль пользователя
        after(
            ProfileActivity::class.java.getDeclaredMethod(
                "getEmojiStatusDrawable",
                TLRPC.EmojiStatus::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ProfileActivity
            val thisClass = ProfileActivity::class.java

            val userId = getFieldValue<Long>(thisClass, thisObject, "userId")!!

            if (userId < 0)
                return@after

            param.result = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                param.args[3] as Int,
                userId
            ) ?: param.result
        }

        after(
            ChatActivity::class.java.getDeclaredMethod("onResume")
        ) { schedulePetFabRefreshForOpenChat() }

        after(
            LaunchActivity::class.java.declaredMethods
                .filter { it.name == "switchToAccount" }
                .maxByOrNull { it.parameterCount }!!
        ) {
            enqueueSelectedAccountInitializationTasks("account switch")
        }

        after(
            ChatActivity::class.java.getDeclaredMethod("onPause")
        ) { AndroidUtilities.runOnUIThread { dismissPetFab() } }

        // Заголовок открытого лс с пользователем
        after(
            ChatAvatarContainer::class.java
                .getDeclaredMethods()
                .filter { it.name == "setTitle" }
                .maxByOrNull { it.parameterCount }!!
        ) { param ->
            val thisObject = param.thisObject as ChatAvatarContainer
            val thisClass = ChatAvatarContainer::class.java
            val titleTextView =
                getFieldValue<SimpleTextView>(thisClass, thisObject, "titleTextView")
                    ?: return@after

            val dialogId = getFieldValue<ChatActivity>(
                thisClass,
                thisObject,
                "parentFragment"
            )?.dialogId ?: return@after

            if (dialogId < 0)
                return@after

            val oldDrawable = titleTextView.rightDrawable
            val newDrawable = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                null,
                dialogId
            ) ?: return@after

            if (oldDrawable !== newDrawable && oldDrawable is AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) {
                titleTextView.rightDrawable = newDrawable
            }

            backgroundScope.launch {
                streaksController.flushCurrentChatPopup()
            }
        }

        // Хук отображения диалоговых окон для замены PremiumPreviewBottomSheet
        before(
            BaseFragment::class.java
                .getDeclaredMethods()
                .filter { it.name == "showDialog" }
                .sortedByDescending { it.parameterCount }[0]
        ) { param ->
            val dialog = param.args[0] as? PremiumPreviewBottomSheet ?: return@before

            val user = getFieldValue<TLRPC.User>(
                PremiumPreviewBottomSheet::class.java,
                dialog,
                "user"
            )!!

            val streakViewData = streaksController.getViewDataBlocking(
                UserConfig.selectedAccount,
                user.id
            ) ?: return@before

            param.args[0] = StreakInfoBottomSheet(dialog, user, streakViewData)
        }

        // Патч пользователя со стриком
        before(
            MessagesController::class.java.getDeclaredMethod(
                "putUser",
                TLRPC.User::class.java,
                Boolean::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val messagesController = param.thisObject as? MessagesController ?: return@before
            val user = param.args[0] as? TLRPC.User ?: return@before
            val accountId =
                getFieldValue<Int>(BaseController::class.java, messagesController, "currentAccount")
                    ?: return@before

            backgroundScope.launch {
                if (streaksController.patchUser(accountId, user))
                    messagesController.putUser(user, false, true)
            }
        }

        fun extractUpdates(accountId: Int, updates: TLRPC.Updates): List<PendingIncomingUpdate> {
            fun resolvePrivatePeerUserId(message: TLRPC.Message): Long? {
                val peerUserId = message.peer_id?.user_id?.takeIf { it > 0L }
                val fromUserId = message.from_id?.user_id?.takeIf { it > 0L }
                val ownerUserId = UserConfig.getInstance(accountId).clientUserId

                return when {
                    message.out -> peerUserId
                    fromUserId != null && fromUserId != ownerUserId -> fromUserId
                    peerUserId != null && peerUserId != ownerUserId -> peerUserId
                    else -> null
                }
            }

            @Suppress("IMPOSSIBLE_IS_CHECK_WARNING", "KotlinConstantConditions")
            return when (updates) {
                is TLRPC.TL_updateShortMessage -> {
                    listOf(
                        PendingIncomingUpdate(
                            updates.user_id,
                            Instant.ofEpochSecond(updates.date.toLong())
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate(),
                            updates.out,
                            updates.id,
                            updates.message
                        )
                    )
                }

                is TLRPC.TL_updates -> {
                    updates.updates.mapNotNull {
                        when (it) {
                            is TLRPC.TL_updateNewMessage -> resolvePrivatePeerUserId(it.message)
                                ?.let { peerUserId ->
                                    PendingIncomingUpdate(
                                        peerUserId,
                                        Instant.ofEpochSecond(it.message.date.toLong())
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate(),
                                        it.message.out,
                                        it.message.id,
                                        it.message.message
                                    )
                                }

                            else -> null
                        }
                    }
                }

                is TLRPC.TL_updatesCombined -> {
                    updates.updates.mapNotNull {
                        when (it) {
                            is TLRPC.TL_updateNewMessage -> resolvePrivatePeerUserId(it.message)
                                ?.let { peerUserId ->
                                    PendingIncomingUpdate(
                                        peerUserId,
                                        Instant.ofEpochSecond(it.message.date.toLong())
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate(),
                                        it.message.out,
                                        it.message.id,
                                        it.message.message
                                    )
                                }

                            else -> null
                        }
                    }
                }

                else -> emptyList()
            }
        }

        fun handleUpdates(accountId: Int, entries: List<PendingIncomingUpdate>) {
            if (accountId != UserConfig.selectedAccount || entries.isEmpty())
                return

            enqueueTask("handle updates for $accountId") {
                var changed = false

                for ((peerUserId, at, out, messageId, message) in entries) {
                    val result =
                        streaksController.handleUpdate(accountId, peerUserId, at, out, message)

                    streakPetsController.handleUpdate(
                        accountId,
                        peerUserId,
                        at,
                        messageId,
                        message,
                        out
                    )

                    refreshOpenedPetDialog(accountId, peerUserId)

                    if (result.changed) {
                        changed = true
                        streaksController.syncUserState(accountId, peerUserId)

                        AndroidUtilities.runOnUIThread {
                            streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                        }
                    }
                }

                if (changed)
                    AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
            }
        }

        // Обработка входящих сообщений
        before(
            MessagesController::class.java.getDeclaredMethod(
                "processUpdates",
                TLRPC.Updates::class.java,
                Boolean::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as? BaseController ?: return@before
            val thisClass = BaseController::class.java

            val accountId =
                getFieldValue<Int>(thisClass, thisObject, "currentAccount") ?: return@before

            if (accountId != UserConfig.selectedAccount)
                return@before

            val updates = param.args[0] as TLRPC.Updates
            val entries = extractUpdates(accountId, updates)

            handleUpdates(accountId, entries)
        }

        // Обработка исходящих сообщений
        before(
            SendMessagesHelper::class.java.getDeclaredMethod(
                "sendMessage",
                SendMessagesHelper.SendMessageParams::class.java
            )
        ) { param ->
            val sendMessageParams = param.args[0] as SendMessagesHelper.SendMessageParams

            enqueueTask("handle outgoing message") {
                val accountId = UserConfig.selectedAccount
                val peerUserId = sendMessageParams.peer

                val result = streaksController.handleUpdate(
                    accountId,
                    peerUserId,
                    LocalDate.now(),
                    true,
                    sendMessageParams.message
                )

                // TODO: fill message id
                streakPetsController.handleUpdate(
                    accountId,
                    peerUserId,
                    LocalDate.now(),
                    0,
                    sendMessageParams.message,
                    true
                )
                refreshOpenedPetDialog(accountId, peerUserId)

                if (result.changed) {
                    streaksController.syncUserState(accountId, peerUserId)

                    AndroidUtilities.runOnUIThread {
                        streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                        streakEmojiRegistry.refreshDialogCells()
                    }
                }
            }
        }
    }
}
