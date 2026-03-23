@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.webkit.ValueCallback
import androidx.room.Room
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import ru.n08i40k.streaks.database.DatabaseBackupManager
import ru.n08i40k.streaks.database.LegacyUsersDbImporter
import ru.n08i40k.streaks.database.MIGRATION_1_2
import ru.n08i40k.streaks.database.MIGRATION_2_3
import ru.n08i40k.streaks.database.MIGRATION_3_5
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import ru.n08i40k.streaks.override.SafeParticlesDrawable
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.registry.LockableActionRegistry
import ru.n08i40k.streaks.registry.LockableCallbackRegistry
import ru.n08i40k.streaks.registry.SafeParticlesDrawableRegistry
import ru.n08i40k.streaks.registry.StreakEmojiRegistry
import ru.n08i40k.streaks.registry.StreakLevelRegistry
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.isClientVersionBelow
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
            reloadPluginCallback: Runnable,
        ) {
            if (INSTANCE != null)
                return

            INSTANCE = Plugin(
                logReceiver,
                translationResolver,
                ResourcesProvider(resourcesRootPath),
                reloadPluginCallback,
            )
            INSTANCE!!.onInject()
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
        fun finalizeInject() {
            INSTANCE?.onFinalizeInject()
        }

        @JvmStatic
        fun eject() {
            INSTANCE?.let {
                it.onEject()
                BulletinHelper.show(null, "Streaks plugin has been ejected!")
            }

            INSTANCE = null
        }
    }

    val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            logger.fatal("An unknown error occurred in background coroutine scope", exception)
        })

    // database
    private val db: PluginDatabase
    private val databaseBackupManager: DatabaseBackupManager

    // helpers
    private val reloadPluginCallback: Runnable
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
    val safeParticlesDrawableRegistry = SafeParticlesDrawableRegistry()

    // controllers
    private val serviceMessagesController = ServiceMessagesController()
    val streaksController: StreaksController
    val streakPetsController: StreakPetsController

    // registries
    val streakLevelRegistry: StreakLevelRegistry = StreakLevelRegistry()


    private val chatMessageCellWidthCache = object : LinkedHashMap<Int, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, Int>): Boolean {
            return size > 32
        }

        fun sameWidth(hash: Int, width: Int): Boolean {
            return this[hash] == width
        }

        fun push(hash: Int, width: Int) {
            this[hash] = width
        }
    }

    constructor(
        logReceiver: LogReceiver,
        translationResolver: TranslationResolver,
        resourcesProvider: ResourcesProvider,
        reloadPluginCallback: Runnable,
    ) {
        this.logger = Logger(logReceiver)
        this.translator = Translator(translationResolver)
        this.resourcesProvider = resourcesProvider
        this.reloadPluginCallback = reloadPluginCallback
        this.bulletinHelper = BulletinHelper(this.translator)

        this.db = Room.databaseBuilder(
            ApplicationLoader.applicationContext,
            PluginDatabase::class.java,
            "tg-streaks"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_5)
            .build()

        this.databaseBackupManager = DatabaseBackupManager(this.db, this.logger::info)

        this.streaksController = StreaksController(this.db, this.resourcesProvider)
        this.streakPetsController =
            StreakPetsController(this.logger, this.db, this.streaksController)
    }

    private fun requestFullPluginReload(reason: String) {
        logger.info(reason)

        try {
            reloadPluginCallback.run()
        } catch (e: Throwable) {
            logger.fatal("Failed to request full plugin reload", e)
        }
    }


    private fun onInject() {
        try {
            registerCallbacks()
        } catch (e: Throwable) {
            logger.fatal("Failed to register callbacks", e)
        }

        logger.info("Injected!")
    }

    private fun onFinalizeInject() {
        val uiLock = CompletableDeferred<Unit>()

        try {
            val importedLegacyDb = runBlocking {
                LegacyUsersDbImporter(db, logger::info).importIfNeeded()
            }

            if (importedLegacyDb) {
                requestFullPluginReload("Legacy database import finished, reloading plugin")
                return
            }

            backgroundScope.launch {
                userConfigAuthorizedIds.forEach { streaksController.patchUsers(it) }
                uiLock.complete(Unit)
            }
        } catch (e: Throwable) {
            logger.fatal("Failed to import legacy database", e)
        }

        try {
            hookMethods()
        } catch (e: Throwable) {
            logger.fatal("Failed to hook methods!", e)
        }

        backgroundScope.launch {
            uiLock.await()

            // refresh dialogs cells and show saved streaks
            delay(250)
            AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }

            syncPeersUi(streaksController.checkAllForUpdates(), refreshAll = true)

            // refresh dialogs cells and show new streaks
            AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
            streakPetsController.checkAllForUpdates()
            streaksController.flushCurrentChatPopup()
        }

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
        backgroundScope.cancel()

        try {
            hooks.forEach { it.unhook() }
            hooks.clear()
        } catch (e: Throwable) {
            logger.fatal("Failed to unhook methods!", e)
        }

        try {
            streakEmojiRegistry.restoreAll()
            safeParticlesDrawableRegistry.restoreAll()

            runBlocking { streaksController.restorePatchedUsers() }
        } catch (e: Throwable) {
            logger.fatal("Failed to restore original SwapAnimatedEmojiDrawable!", e)
        }

        chatContextMenuCallbackRegistry.clear()
        settingsActionCallbackRegistry.clear()

        logger.info("Ejected!")
    }

    private suspend fun syncPeerUi(accountId: Int, peerUserId: Long) {
        streaksController.syncUserState(accountId, peerUserId)

        AndroidUtilities.runOnUIThread {
            streakEmojiRegistry.refreshByPeerUserId(peerUserId)
            streakEmojiRegistry.refreshDialogCells()
        }
    }

    private suspend fun syncPeersUi(
        targets: Iterable<StreaksController.UiSyncTarget>,
        refreshAll: Boolean = false,
    ) {
        val syncTargets = targets.distinct()

        syncTargets.forEach { streaksController.syncUserState(it.accountId, it.peerUserId) }

        AndroidUtilities.runOnUIThread {
            if (refreshAll) {
                streakEmojiRegistry.refreshAll()
                return@runOnUIThread
            }

            syncTargets
                .asSequence()
                .map { it.peerUserId }
                .toSet()
                .forEach(streakEmojiRegistry::refreshByPeerUserId)

            streakEmojiRegistry.refreshDialogCells()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun registerCallbacks() {
        fun add(key: String, callback: (Long) -> Unit) {
            chatContextMenuCallbackRegistry.register(key) {
                try {
                    callback(it)
                } catch (e: Throwable) {
                    logger.fatal("An error occurred while handling context menu entry touch", e)
                }
            }
        }

        fun addSettingAction(key: String, callback: () -> Unit) {
            settingsActionCallbackRegistry.register(key) {
                try {
                    callback()
                } catch (e: Throwable) {
                    logger.fatal("An error occurred while handling settings action", e)
                }
            }
        }

        fun validateDebugPeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            if (peerUserId <= 0L || peerUserId == ownerUserId) {
                bulletinHelper.showTranslated(TranslationKey.INFO_DEBUG_PRIVATE_USER_ONLY)
                return null
            }

            val peer = MessagesController.getInstance(accountId).getUser(peerUserId)

            if (
                peer == null
                || UserObject.isBot(peer)
                || UserObject.isDeleted(peer)
                || UserObject.isReplyUser(peer)
                || UserObject.isUserSelf(peer)
            ) {
                bulletinHelper.showTranslated(TranslationKey.INFO_DEBUG_PRIVATE_USER_ONLY)
                return null
            }

            return peer
        }

        fun validatePrivatePeer(accountId: Int, peerUserId: Long): TLRPC.User? {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            if (peerUserId <= 0L || peerUserId == ownerUserId) {
                bulletinHelper.showTranslated(TranslationKey.INFO_PRIVATE_USER_ONLY)
                return null
            }

            val peer = MessagesController.getInstance(accountId).getUser(peerUserId) ?: run {
                bulletinHelper.showTranslated(TranslationKey.INFO_PRIVATE_USER_ONLY)
                return null
            }

            if (UserObject.isBot(peer)) {
                bulletinHelper.showTranslated(TranslationKey.INFO_ACTION_NOT_AVAILABLE_FOR_BOTS)
                return null
            }

            if (UserObject.isDeleted(peer)) {
                bulletinHelper.showTranslated(TranslationKey.INFO_ACTION_NOT_AVAILABLE_FOR_DELETED_USERS)
                return null
            }

            return peer
        }

        add(ChatContextMenuButton.REBUILD) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val peer = MessagesController.getInstance(accountId).getUser(peerUserId) ?: return@add

            backgroundScope.launch {
                val revives = db
                    .streakReviveDao()
                    .findByRelation(ownerUserId, peerUserId)
                    .map { it.revivedAt }
                    .toSet()

                streaksController.rebuild(accountId, peer, revives) { progress ->
                    progress.showBulletin()
                }

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
                            "peer_name" to (peer.username?.takeIf { it.isNotBlank() }
                                ?.let { "@$it" }
                                ?: UserObject.getUserName(peer).takeIf { it.isNotBlank() }
                                ?: peer.id.toString()),
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
            val peer = validatePrivatePeer(accountId, peerUserId) ?: return@add

            if (streakPetsController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.INFO_FORCE_CHECK_ALREADY_RUNNING)
                return@add
            }

            backgroundScope.launch {
                val streakPet = streakPetsController.get(accountId, peerUserId)

                if (streakPet == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_PET_FOR_CHAT)
                    return@launch
                }

                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@launch
                }

                streakPetsController.rebuild(accountId, peer) { progress ->
                    progress.showBulletin()
                }
            }

            logger.info("[Context Menu] Rebuild pet clicked on $peerUserId")
        }

        add(ChatContextMenuButton.CREATE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            validatePrivatePeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                if (streakPetsController.get(accountId, peerUserId) != null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_PET_ALREADY_EXISTS_FOR_CHAT)
                    return@launch
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
                                serviceMessagesController.sendPetInvite(accountId, peerUserId)
                            }
                            .setNegativeButton(
                                translator.translate(TranslationKey.DIALOG_CREATE_STREAK_PET_NO)
                            ) { _, _ ->
                                backgroundScope.launch {
                                    when (streakPetsController.create(accountId, peerUserId)) {
                                        is StreakPetsController.CreateResult.Created -> {
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

            backgroundScope.launch {
                try {
                    val streak = streaksController.get(accountId, peerUserId)

                    if (streak == null) {
                        bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                        return@launch
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
            val peer = validatePrivatePeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@launch
                }

                if (!streak.dead) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_NOT_ENDED_YET)
                    return@launch
                }

                if (!streak.canRevive) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                    return@launch
                }

                if (!streaksController.reviveNow(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                    return@launch
                }

                streaksController.patchUser(accountId, peer)
                MessagesController.getInstance(accountId).putUser(peer, false, true)
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
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                streaksController.debugSetThreeDayStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_SET_3,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-create clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_UPGRADE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                val streak = streaksController.get(accountId, peerUserId)

                if (streak == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@launch
                }

                val nextLevel = streakLevelRegistry
                    .levels()
                    .firstOrNull { level -> level.length > streak.level.length }

                if (nextLevel == null) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_DEBUG_STREAK_ALREADY_MAX)
                    return@launch
                }

                val newLength =
                    streaksController.debugUpgradeStreak(accountId, peerUserId) ?: return@launch
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_UPGRADED,
                    mapOf("days" to newLength.toString()),
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-upgrade clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_FREEZE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                streaksController.debugFreezeStreak(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_FROZEN,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-freeze clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_KILL) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                streaksController.debugMarkDead(accountId, peerUserId)
                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_MARKED_DEAD,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-kill clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                if (!streaksController.debugDeleteStreak(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                    return@launch
                }

                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_DELETED,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-delete clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_DELETE_PET) { peerUserId ->
            val accountId = UserConfig.selectedAccount
            val peer = validateDebugPeer(accountId, peerUserId) ?: return@add

            backgroundScope.launch {
                if (!streakPetsController.delete(accountId, peerUserId)) {
                    bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_PET_FOR_CHAT)
                    return@launch
                }

                syncPeerUi(accountId, peerUserId)
                bulletinHelper.showTranslated(
                    TranslationKey.OK_DEBUG_STREAK_PET_DELETED,
                    "msg_reactions"
                )
            }

            logger.info("[Context Menu] Debug-delete-pet clicked on ${peer.id}")
        }

        add(ChatContextMenuButton.DEBUG_CRASH) { _ ->
            throw RuntimeException("Crash button was pressed")
        }

        addSettingAction(SettingsActionButton.REBUILD_ALL) {
            val accountId = UserConfig.selectedAccount

            if (streaksController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.INFO_FORCE_CHECK_ALREADY_RUNNING)
                return@addSettingAction
            }

            bulletinHelper.showTranslated(
                TranslationKey.INFO_FORCE_CHECK_STARTED_ALL,
                "msg_retry"
            )

            backgroundScope.launch {
                try {
                    val result =
                        streaksController.rebuildAll(accountId) { index, total, _, progress ->
                            bulletinHelper.showTranslated(
                                TranslationKey.FORCE_CHECK_DAY_PROGRESS_ALL_SIMPLE,
                                mapOf(
                                    "peer_name" to progress.user.label,
                                    "days_checked" to progress.daysChecked.toString(),
                                    "checked_chats" to (index + 1).toString(),
                                    "total_chats" to total.toString(),
                                ),
                                "msg_retry"
                            )
                        }

                    syncPeersUi(result.uiSyncTargets, refreshAll = true)

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

        addSettingAction(SettingsActionButton.EXPORT_BACKUP_NOW) {
            backgroundScope.launch {
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

        addSettingAction(SettingsActionButton.IMPORT_LATEST_BACKUP) {
            backgroundScope.launch {
                try {
                    val backup = databaseBackupManager.restoreLatest()
                    logger.info("Database backup restore completed, reloading plugin: ${backup.name}")
                    requestFullPluginReload("Reload requested after database backup restore")
                } catch (e: IllegalStateException) {
                    if (e.message == TranslationKey.DB_ERR_NO_BACKUPS_FOUND) {
                        bulletinHelper.showTranslated(TranslationKey.DB_ERR_NO_BACKUPS_FOUND)
                    } else {
                        logger.fatal("Failed to restore latest database backup", e)
                        bulletinHelper.showTranslated(
                            TranslationKey.DB_ERR_FAILED_APPLY_BACKUP,
                            mapOf("reason" to (e.message ?: e.javaClass.simpleName)),
                            "msg_reset"
                        )
                    }
                } catch (e: Throwable) {
                    logger.fatal("Failed to restore latest database backup", e)
                    bulletinHelper.showTranslated(
                        TranslationKey.DB_ERR_FAILED_APPLY_BACKUP,
                        mapOf("reason" to (e.message ?: e.javaClass.simpleName)),
                        "msg_reset"
                    )
                }
            }
        }

        chatContextMenuCallbackRegistry.freeze()
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

                val height = AndroidUtilities.dp(22f)
                emojiStatusView.layout(0, 0, height * 3, height)
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
                true
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
                    backgroundScope.launch {
                        val accountId = UserConfig.selectedAccount
                        val peerUserId = messageObject.dialogId

                        val streak = streaksController.get(accountId, peerUserId)

                        if (streak == null) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_NO_STREAK_RECORD_FOR_CHAT)
                            return@launch
                        }

                        if (!streak.dead) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_NOT_ENDED_YET)
                            return@launch
                        }

                        if (!streak.canRevive) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                            return@launch
                        }

                        if (!streaksController.reviveNow(accountId, peerUserId)) {
                            bulletinHelper.showTranslated(TranslationKey.INFO_STREAK_RESTORE_UNAVAILABLE)
                            return@launch
                        }
                    }
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    backgroundScope.launch {
                        val accountId = UserConfig.selectedAccount
                        val peerUserId = messageObject.dialogId

                        when (streakPetsController.create(accountId, peerUserId)) {
                            is StreakPetsController.CreateResult.Created -> {
                                serviceMessagesController.sendPetInviteAccepted(
                                    accountId,
                                    peerUserId
                                )
                                syncPeerUi(accountId, peerUserId)
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

            val hash = System.identityHashCode(thisObject)

            if (!chatMessageCellWidthCache.sameWidth(hash, thisObject.backgroundWidth)) {
                thisObject.backgroundWidth += streakEmoji.getAdditionalWidth()
                chatMessageCellWidthCache.push(hash, thisObject.backgroundWidth)
            }
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

            SafeParticlesDrawable.encapsulate(
                oldEmojiStatus2,
                thisObject,
                getField(thisClass, "emojiStatus2"),
                nameTextView
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

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                param.args[3] as Int,
                userId
            )
        }

        // Заголовок открытого лс с пользователем
        after(
            ChatAvatarContainer::class.java
                .getDeclaredMethods()
                .filter { it.name == "setTitle" }
                .maxByOrNull { it.parameterCount }!!
        ) { param ->
            val thisObject = param.thisObject as ChatAvatarContainer
            val thisClass = ChatAvatarContainer::class.java

            val dialogId = getFieldValue<ChatActivity>(
                thisClass,
                thisObject,
                "parentFragment"
            )?.dialogId ?: return@after

            if (dialogId < 0)
                return@after

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                null,
                dialogId
            )

            backgroundScope.launch {
                streaksController.flushCurrentChatPopup()
            }
        }

        // Хук отображения диалоговых окон для замены PremiumPreviewBottomSheet
        before(
            BaseFragment::class.java.getDeclaredMethod("showDialog", Dialog::class.java)
        ) { param ->
            val dialog = param.args[0] as? PremiumPreviewBottomSheet ?: return@before
            val user = getFieldValue<TLRPC.User>(
                PremiumPreviewBottomSheet::class.java,
                dialog,
                "user"
            )!!

            val streakViewData = runBlocking {
                this@Plugin.streaksController.getViewData(
                    UserConfig.selectedAccount,
                    user.id
                )
            } ?: return@before

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
            val user = param.args[0] as? TLRPC.User ?: return@before

            runBlocking {
                streaksController.patchUser(UserConfig.selectedAccount, user)
            }
        }

        fun handleUpdates(accountId: Int, updates: TLRPC.Updates) {
            data class Update(
                val peerUserId: Long,
                val at: LocalDate,
                val out: Boolean,
                val messageId: Int,
                val message: String?
            )

            @Suppress("IMPOSSIBLE_IS_CHECK_WARNING", "KotlinConstantConditions")
            val entries = when (updates) {
                is TLRPC.TL_updateShortMessage -> {
                    setOf(
                        Update(
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
                            is TLRPC.TL_updateNewMessage -> Update(
                                it.message.peer_id.user_id,
                                Instant.ofEpochSecond(it.message.date.toLong())
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate(),
                                it.message.out,
                                it.message.id,
                                it.message.message
                            )

                            else -> null
                        }
                    }
                }

                is TLRPC.TL_updatesCombined -> {
                    updates.updates.mapNotNull {
                        when (it) {
                            is TLRPC.TL_updateNewMessage -> Update(
                                it.message.peer_id.user_id,
                                Instant.ofEpochSecond(it.message.date.toLong())
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate(),
                                it.message.out,
                                it.message.id,
                                it.message.message
                            )

                            else -> null
                        }
                    }
                }

                else -> setOf()
            }

            @Suppress("KotlinConstantConditions")
            backgroundScope.launch {
                var changed = false

                for ((peerUserId, at, out, messageId, message) in entries) {
                    val result = streaksController.handleUpdate(accountId, peerUserId, out, message)
                    streakPetsController.handleUpdate(
                        accountId,
                        peerUserId,
                        at,
                        messageId,
                        message,
                        out
                    )

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

            val updates = param.args[0] as TLRPC.Updates

            handleUpdates(accountId, updates)
        }

        // Обработка исходящих сообщений
        before(
            SendMessagesHelper::class.java.getDeclaredMethod(
                "sendMessage",
                SendMessagesHelper.SendMessageParams::class.java
            )
        ) { param ->
            val sendMessageParams = param.args[0] as SendMessagesHelper.SendMessageParams

            backgroundScope.launch {
                val accountId = UserConfig.selectedAccount
                val peerUserId = sendMessageParams.peer

                val result = streaksController.handleUpdate(
                    accountId,
                    peerUserId,
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
