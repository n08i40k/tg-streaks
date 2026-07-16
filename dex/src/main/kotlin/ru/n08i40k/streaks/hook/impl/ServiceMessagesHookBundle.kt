package ru.n08i40k.streaks.hook.impl

import android.graphics.Bitmap
import androidx.collection.LongSparseArray
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
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getFieldValue
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
                ?: return@before

            param.args[1] = TLRPC.TL_messageService()
                .apply {
                    cloneFields(message, this, TLRPC.Message::class.java)

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
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@before

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            val accountId = UserConfig.selectedAccount
            val peerUserId = messageObject.dialogId

            val plugin = Plugin.getInstance()

            val streaksController = plugin.streaksController
            val serviceMessagesController = plugin.serviceMessagesController
            val streakPetsController = plugin.streakPetsController

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
                        serviceMessagesController.setEnabled(accountId, peerUserId, true)
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
                                nc.removeObserver(this, NotificationCenter.fileLoaded)
                                nc.removeObserver(this, NotificationCenter.fileLoadFailed)
                                unsubscribe()

                                result.complete(null)
                            }
                        }

                        nc.addObserver(observer, NotificationCenter.fileLoaded)
                        nc.addObserver(observer, NotificationCenter.fileLoadFailed)

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
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@before

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    param.args[0] = Strings.service_streak_ended_title()
                    param.args[1] =
                        Strings.service_streak_ended_subtitle()
                    param.args[3] = Strings.service_streak_ended_hint()
                    param.args[5] =
                        Strings.service_streak_ended_action()
                    param.args[9] = false
                    param.args[10] = true
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    param.args[0] =
                        Strings.service_pet_invite_title()
                    param.args[1] =
                        Strings.service_pet_invite_description()
                    param.args[3] =
                        Strings.service_pet_invite_hint()
                    param.args[5] =
                        Strings.service_pet_invite_action()
                    param.args[9] = false
                    param.args[10] = true
                }

                ServiceMessage.SYNC_OFFER -> {
                    param.args[0] =
                        Strings.service_sync_offer_peer_title()
                    param.args[1] =
                        Strings.service_sync_offer_peer_subtitle()
                    param.args[3] =
                        Strings.service_sync_offer_peer_hint()
                    param.args[5] =
                        Strings.service_sync_offer_peer_action()
                    param.args[9] = false
                    param.args[10] = true
                }
            }
        }

        // "Новый" ui у gift
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

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT
                && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT
                && prizeStars.transaction_id != ServiceMessage.SYNC_OFFER
            )
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
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@after

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT
                && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT
                && prizeStars.transaction_id != ServiceMessage.SYNC_OFFER
            )
                return@after

            param.result = -AndroidUtilities.dp(19.5f)
        }

        // Удаление анимации у gift
        after(
            ChatActionCell::class.java.getDeclaredMethod(
                "setMessageObject",
                MessageObject::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val messageObject = param.args[0] as? MessageObject
                ?: return@after

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@after

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT
                && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT
                && prizeStars.transaction_id != ServiceMessage.SYNC_OFFER
            )
                return@after

            val thisObject = param.thisObject as ChatActionCell
            val thisClass = ChatActionCell::class.java

            getFieldValue<ImageReceiver>(thisClass, thisObject, "imageReceiver")
                ?.apply {
                    setAllowStartLottieAnimation(false)
                    setDelegate(null)
                    setImageBitmap(null as Bitmap?)
                    clearImage()
                    clearDecorators()
                    setVisible(false, true)
                }
        }
    }
}
