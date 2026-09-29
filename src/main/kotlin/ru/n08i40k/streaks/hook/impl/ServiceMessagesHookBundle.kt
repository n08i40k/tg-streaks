package ru.n08i40k.streaks.hook.impl

import android.graphics.Bitmap
import androidx.collection.LongSparseArray
import de.robv.android.xposed.XC_MethodHook
import kotlinx.coroutines.CompletableDeferred
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatActionCell
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.emojiPack.share.StreakEmojiPackImportBottomSheet
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.`ChatActionCell$currentMessageObject`
import ru.n08i40k.streaks.util.`ChatActionCell$imageReceiver`
import ru.n08i40k.streaks.util.`ChatActionCell$setStarsPaused`
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.StreakEmojiPackCodec
import ru.n08i40k.streaks.util.`TLRPC$Message$$fields`
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.isClientVersionBelow
import ru.n08i40k.streaks.util.runOnMainThread
import java.io.File
import java.util.AbstractMap
import kotlin.time.Clock

class ServiceMessagesHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // это кстати хук MessageObject для полной замены вида сервисных сообщений
        before(
            MessageObject::class.java.getDeclaredConstructor(
                Int::class.java,
                TLRPC.Message::class.java,
                MessageObject::class.java,
                AbstractMap::class.java,
                AbstractMap::class.java,
                LongSparseArray::class.java,
                LongSparseArray::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Long::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )
        ) { param ->
            val message = param.args[1] as? TLRPC.Message
                ?: return@before

            val currentAccount = param.args[0] as? Int ?: 0

            if (message.message == null)
                return@before

            if (!ServiceMessage.isServiceText(message.message))
                return@before

            val tryStreakCreate = streakCreate@{
                if (message.message != ServiceMessage.CREATE_TEXT)
                    return@streakCreate null

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = Strings.service_streak_started_text() }
            }

            val tryStreakUpgrade = streakUpgrade@{
                val days = ServiceMessage.UPGRADE_REGEX
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@streakUpgrade null

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = Strings.service_streak_level_up_text(days) }
            }

            val tryStreakDeath = streakDeath@{
                if (message.message != ServiceMessage.DEATH_TEXT)
                    return@streakDeath null

                TLRPC.TL_messageActionPrizeStars()
                    .apply {
                        boost_peer = message.peer_id
                        flags = 0
                        giveaway_msg_id = 0
                        stars = 0
                        transaction_id = ServiceMessage.DEATH_TEXT
                        unclaimed = false
                    }
            }

            val tryStreakRestore = streakRestore@{
                if (message.message != ServiceMessage.RESTORE_TEXT)
                    return@streakRestore null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                val messageText = if (byPeer) {
                    val peerName = peerId
                        .let { MessagesController.getInstance(currentAccount).getUser(it) }
                        ?.let { UserObject.getUserName(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    Strings.service_streak_restored_peer(peerName)
                } else {
                    Strings.service_streak_restored_self()
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val tryPetInvite = petInvite@{
                if (message.message != ServiceMessage.PET_INVITE_TEXT)
                    return@petInvite null

                if (message.out) {
                    TLRPC.TL_messageActionCustomAction()
                        .apply { this.message = Strings.service_pet_invite_sent_self() }
                } else {
                    TLRPC.TL_messageActionPrizeStars()
                        .apply {
                            boost_peer = message.peer_id
                            flags = 0
                            giveaway_msg_id = 0
                            stars = 0
                            transaction_id = ServiceMessage.PET_INVITE_TEXT
                            unclaimed = false
                        }
                }
            }

            val tryPetInviteAccepted = petInviteAccepted@{
                if (message.message != ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
                    return@petInviteAccepted null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                val messageText = if (byPeer) {
                    val peerName = peerId
                        .let { MessagesController.getInstance(currentAccount).getUser(it) }
                        ?.let { UserObject.getUserName(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    Strings.service_pet_invite_accepted_peer(peerName)
                } else {
                    Strings.service_pet_invite_accepted_self()
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val tryPetSetName = petSetName@{
                val name = ServiceMessage.PET_SET_NAME_REGEX
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@petSetName null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                val messageText = if (byPeer) {
                    val peerName = peerId
                        .let { MessagesController.getInstance(currentAccount).getUser(it) }
                        ?.let { UserObject.getUserName(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    Strings.service_pet_rename_peer(peerName, name)
                } else {
                    Strings.service_pet_rename_self(name)
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val tryPetDeleted = petDeleted@{
                if (message.message != ServiceMessage.PET_DELETED_TEXT)
                    return@petDeleted null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                val messageText = if (byPeer) {
                    val peerName = peerId
                        .let { MessagesController.getInstance(currentAccount).getUser(it) }
                        ?.let { UserObject.getUserName(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    Strings.service_pet_delete_peer(peerName)
                } else {
                    Strings.service_pet_delete_self()
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val trySyncOffer = syncOffer@{
                if (message.message != ServiceMessage.SYNC_OFFER)
                    return@syncOffer null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                if (byPeer) {
                    TLRPC.TL_messageActionPrizeStars()
                        .apply {
                            boost_peer = message.peer_id
                            flags = 0
                            giveaway_msg_id = 0
                            stars = 0
                            transaction_id = ServiceMessage.SYNC_OFFER
                            unclaimed = false
                        }
                } else {
                    TLRPC.TL_messageActionCustomAction()
                        .apply { this.message = Strings.service_sync_offer_self_text() }
                }
            }

            val trySyncApplied = syncApplied@{
                if (message.message != ServiceMessage.SYNC_APPLIED)
                    return@syncApplied null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val byPeer = peerId != null
                        && fromId != null
                        && peerId > 0
                        && fromId == peerId

                val messageText = if (byPeer) {
                    val peerName = peerId
                        .let { MessagesController.getInstance(currentAccount).getUser(it) }
                        ?.let { UserObject.getUserName(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    Strings.service_sync_applied_peer_text(peerName)
                } else {
                    Strings.service_sync_applied_self_text()
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val tryEmojiPackImport = emojiPackImport@{
                val pack = StreakEmojiPackCodec.decode(message.message)
                    ?: return@emojiPackImport null

                if (message.out) {
                    TLRPC.TL_messageActionCustomAction()
                        .apply { this.message = Strings.service_emoji_pack_import_self(pack.name) }
                } else {
                    TLRPC.TL_messageActionPrizeStars()
                        .apply {
                            boost_peer = message.peer_id
                            flags = 0
                            giveaway_msg_id = 0
                            stars = 0
                            transaction_id = message.message
                            unclaimed = false
                        }
                }
            }

            val action = tryStreakCreate()
                ?: tryStreakUpgrade()
                ?: tryStreakDeath()
                ?: tryStreakRestore()
                ?: tryPetInvite()
                ?: tryPetInviteAccepted()
                ?: tryPetSetName()
                ?: tryPetDeleted()
                ?: trySyncOffer()
                ?: trySyncApplied()
                ?: tryEmojiPackImport()
                ?: return@before

            param.args[1] = TLRPC.TL_messageService()
                .apply {
                    cloneFields(message, this, `TLRPC$Message$$fields`)

                    this.action = action
                    this.message = null
                }
        }

        // Текст короткого сообщения в списке чатов
        after(
            MessageObject::class.java.getDeclaredMethod(
                "updateMessageText",
                AbstractMap::class.java,
                AbstractMap::class.java,
                LongSparseArray::class.java,
                LongSparseArray::class.java,
            )
        ) { param ->
            val thisObject = param.thisObject as MessageObject

            val prizeStars = thisObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (ServiceMessage.isEmojiPackImport(prizeStars.transaction_id)) {
                thisObject.messageText = Strings.service_emoji_pack_import_title()
                return@after
            }

            thisObject.messageText = when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> Strings.service_streak_ended_title()
                ServiceMessage.PET_INVITE_TEXT -> Strings.service_pet_invite_title()
                ServiceMessage.SYNC_OFFER -> Strings.service_sync_offer_peer_title()
                else -> return@after
            }
        }

        // Callback для кнопки у сервисного сообщения основанного на gift
        before(
            ChatActionCell::class.java.getDeclaredMethod(
                "openStarsGiftTransaction",
            )
        ) { param ->
            val messageObject =
                `ChatActionCell$currentMessageObject`.invokeExact(param.thisObject as ChatActionCell) as? MessageObject?
                    ?: return@before

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            val accountId = UserConfig.selectedAccount
            val peerUserId = messageObject.dialogId

            val plugin = Plugin.getInstance()

            val streaksController = plugin.streaksController
            val serviceMessagesController = plugin.serviceMessagesController
            val streakPetsController = plugin.streakPetsController
            val pluginRelationController = plugin.pluginRelationController
            val streakEmojiPacksController = plugin.streakEmojiPacksController

            if (ServiceMessage.isEmojiPackImport(prizeStars.transaction_id)) {
                val context = (param.thisObject as ChatActionCell).context

                val pack = StreakEmojiPackCodec.decode(prizeStars.transaction_id)

                if (pack == null) {
                    BulletinHelper.show(Strings.status_error_emoji_pack_invalid())

                    param.result = null
                    return@before
                }

                plugin.enqueueTask("open emoji pack import sheet for ${pack.id}") {
                    val replace = streakEmojiPacksController.exists(pack.id)

                    runOnMainThread {
                        StreakEmojiPackImportBottomSheet(context, pack, replace) {
                            plugin.enqueueTask("import emoji pack ${pack.id}") {
                                streakEmojiPacksController.import(pack)

                                BulletinHelper.show(
                                    if (replace)
                                        Strings.status_success_emoji_pack_replaced()
                                    else
                                        Strings.status_success_emoji_pack_imported(),
                                    "msg_reactions"
                                )
                            }
                        }.show()
                    }
                }

                param.result = null
                return@before
            }

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "try to revive streak from notification"
                    ) {
                        when (val streak = streaksController.get(accountId, peerUserId)) {
                            null ->
                                BulletinHelper.show(Strings.status_info_streak_not_found_for_chat())

                            else if !streak.ended ->
                                BulletinHelper.show(Strings.status_info_streak_not_ended_yet())

                            else if !streak.canRestore ->
                                BulletinHelper.show(Strings.status_info_streak_restore_unavailable())

                            else if !streaksController.restore(
                                accountId,
                                peerUserId,
                                Clock.System.now()
                            ) ->
                                BulletinHelper.show(Strings.status_info_streak_restore_unavailable())
                        }
                    }
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "try to accept streak-pet invitation from notification"
                    ) {
                        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

                        if (!pluginRelationController.hasPlugin(ownerUserId, peerUserId))
                            pluginRelationController.setHasPlugin(ownerUserId, peerUserId, true)

                        serviceMessagesController.sendPetInviteAccepted(accountId, peerUserId)

                        if (!streakPetsController.create(accountId, peerUserId, byInvite = true)) {
                            BulletinHelper.show(Strings.status_info_pet_already_exists_for_chat())
                            return@enqueue
                        }

                        BulletinHelper.show(Strings.status_success_pet_created(), "msg_reactions")
                    }
                }

                ServiceMessage.SYNC_OFFER -> {
                    suspend fun getFile(): File? {
                        val message = MessagesStorage
                            .getInstance(accountId)
                            .getMessage(peerUserId, messageObject.id.toLong())
                            ?: run {
                                Logger.info("Failed to get cached message for $accountId:$peerUserId:${messageObject.id}")
                                BulletinHelper.show(Strings.status_error_sync_message_not_found())
                                return null
                            }

                        val document = MessageObject.getDocument(message)
                            ?: run {
                                Logger.info("Cached message $accountId:$peerUserId:${messageObject.id} doesn't contains any document!")
                                BulletinHelper.show(Strings.status_error_sync_file_missing())
                                return null
                            }

                        val fileLoader = FileLoader.getInstance(accountId)

                        fileLoader.getPathToAttach(document, false)
                            ?.takeIf { it.exists() }
                            ?.run { return this }

                        val nc = NotificationCenter.getInstance(accountId)

                        val result = CompletableDeferred<File?>()

                        val observer = object : NotificationCenter.NotificationCenterDelegate,
                            EjectNotifier.Delegate {
                            private val unsubscribe = EjectNotifier.subscribe(this)

                            override fun didReceivedNotification(
                                id: Int,
                                accountId: Int,
                                vararg args: Any?
                            ) {
                                if (args[0] != FileLoader.getAttachFileName(document))
                                    return

                                when (id) {
                                    NotificationCenter.fileLoaded ->
                                        result.complete(args[1] as File)

                                    NotificationCenter.fileLoadFailed -> {
                                        Logger.info("Failed to download db snapshot for $accountId:$peerUserId:${messageObject.id}")
                                        BulletinHelper.show(Strings.status_error_sync_download_failed())
                                    }

                                    else ->
                                        throw IllegalArgumentException("Invalid notification id $id")
                                }

                                destroy()
                            }

                            override fun onEject() =
                                destroy()

                            fun destroy() {
                                runOnMainThread {
                                    nc.removeObserver(this, NotificationCenter.fileLoaded)
                                    nc.removeObserver(this, NotificationCenter.fileLoadFailed)
                                }

                                unsubscribe()

                                result.complete(null)
                            }
                        }

                        runOnMainThread {
                            nc.addObserver(observer, NotificationCenter.fileLoaded)
                            nc.addObserver(observer, NotificationCenter.fileLoadFailed)
                        }

                        fileLoader.loadFile(document, message, FileLoader.PRIORITY_HIGH, 0)

                        return result.await()
                    }

                    // do not try/catch this block as the exception SHOULD be reported (ATE try/catch doesn't count)
                    AccountTaskExecutor.enqueue(accountId, "apply sync") {
                        val sourceFile = getFile()
                            ?: return@enqueue

                        plugin.databaseBackupManager.importSwappedNow(
                            sourceFile,
                            UserConfig.getInstance(accountId).clientUserId,
                            peerUserId
                        )

                        EventBus.emit(
                            PluginEvent.SyncDatabaseSnapshotAppliedEvent(
                                accountId,
                                peerUserId,
                                streaksController.getViewData(accountId, peerUserId) != null,
                                streakPetsController.exists(accountId, peerUserId)
                            )
                        )

                        serviceMessagesController.sendSyncApplied(accountId, peerUserId)
                        BulletinHelper.show(Strings.status_success_sync_applied())
                    }
                }

                else -> return@before
            }

            param.result = null
        }

        // Текст у сервисных сообщений основанных на gift
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
            val messageObject =
                `ChatActionCell$currentMessageObject`.invokeExact(param.thisObject as ChatActionCell) as? MessageObject?
                    ?: return@before

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            if (ServiceMessage.isEmojiPackImport(prizeStars.transaction_id)) {
                val pack = StreakEmojiPackCodec.decode(prizeStars.transaction_id)
                    ?: return@before

                applyGiftLayout(
                    param,
                    Strings.service_emoji_pack_import_title(),
                    pack.name,
                    Strings.service_emoji_pack_import_hint(),
                    Strings.service_emoji_pack_import_action(),
                )
                return@before
            }

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> applyGiftLayout(
                    param,
                    Strings.service_streak_ended_title(),
                    Strings.service_streak_ended_subtitle(),
                    Strings.service_streak_ended_hint(),
                    Strings.service_streak_ended_action(),
                )

                ServiceMessage.PET_INVITE_TEXT -> applyGiftLayout(
                    param,
                    Strings.service_pet_invite_title(),
                    Strings.service_pet_invite_description(),
                    Strings.service_pet_invite_hint(),
                    Strings.service_pet_invite_action(),
                )

                ServiceMessage.SYNC_OFFER -> applyGiftLayout(
                    param,
                    Strings.service_sync_offer_peer_title(),
                    Strings.service_sync_offer_peer_subtitle(),
                    Strings.service_sync_offer_peer_hint(),
                    Strings.service_sync_offer_peer_action(),
                )
            }
        }

        // "Новый" ui у gift
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "isNewStyleButtonLayout",
            )
        ) { param ->
            val messageObject =
                `ChatActionCell$currentMessageObject`.invokeExact(param.thisObject as ChatActionCell) as? MessageObject?
                    ?: return@after

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (!ServiceMessage.isGiftStyled(prizeStars.transaction_id))
                return@after

            param.result = true
        }

        // Фикс размеров gift
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "getImageSize",
                MessageObject::class.java
            )
        ) { param ->
            val messageObject =
                `ChatActionCell$currentMessageObject`.invokeExact(param.thisObject as ChatActionCell) as? MessageObject?
                    ?: return@after

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (!ServiceMessage.isGiftStyled(prizeStars.transaction_id))
                return@after


            param.result = if (isClientVersionBelow("12.2.0"))
                -AndroidUtilities.dp(400f) // client will clamp this value by itself (believe me)
            else
                -AndroidUtilities.dp(19.5f)
        }

        // Звёздочки на кнопке перерисовывают сообщение каждый кадр пока оно на экране
        after(
            ChatActionCell::class.java.getDeclaredMethod("onAttachedToWindow")
        ) { param ->
            val thisObject = param.thisObject as ChatActionCell

            if (isGiftStyled(thisObject.messageObject))
                `ChatActionCell$setStarsPaused`.invokeExact(thisObject, true)
        }

        // Удаление анимации у gift
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "setMessageObject",
                MessageObject::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val thisObject = param.thisObject as ChatActionCell
            val giftStyled = isGiftStyled(param.args[0] as? MessageObject)

            `ChatActionCell$setStarsPaused`.invokeExact(
                thisObject,
                giftStyled || !thisObject.isAttachedToWindow
            )

            if (!giftStyled)
                return@after

            (`ChatActionCell$imageReceiver`.invokeExact(thisObject) as ImageReceiver)
                .apply {
                    setAllowStartLottieAnimation(false)
                    setDelegate(null)
                    setImageBitmap(null as Bitmap?)
                    clearImage()
                    clearDecorators()
                    setVisible(false, true)
                }
        }
    }

    private fun applyGiftLayout(
        param: XC_MethodHook.MethodHookParam,
        title: CharSequence,
        subtitle: CharSequence,
        hint: CharSequence,
        action: CharSequence,
    ) {
        param.args[0] = title
        param.args[1] = "$subtitle\n$hint"
        param.args[3] = null
        param.args[5] = action
        param.args[9] = false
        param.args[10] = true
    }

    private fun isGiftStyled(messageObject: MessageObject?): Boolean {
        val prizeStars = messageObject?.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
            ?: return false

        return ServiceMessage.isGiftStyled(prizeStars.transaction_id)
    }
}
