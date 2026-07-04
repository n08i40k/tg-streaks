package ru.n08i40k.streaks.hook.impl

import android.graphics.Bitmap
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import androidx.collection.LongSparseArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.Cells.ChatActionCell
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getFieldValue
import java.util.AbstractMap

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

            val tryStreakCreate = streakCreate@{
                if (message.message != ServiceMessage.CREATE_TEXT)
                    return@streakCreate null

                TLRPC.TL_messageActionCustomAction()
                    .apply {
                        this.message =
                            Translator.translate(TranslationKey.Service.Streak.STARTED_TEXT)
                    }
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
                    .apply {
                        this.message = Translator.translate(
                            TranslationKey.Service.Streak.LEVEL_UP_TEXT,
                            mapOf("days" to days.toString())
                        )
                    }
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

                    Translator.translate(
                        TranslationKey.Service.Streak.RESTORED_PEER,
                        mapOf("name" to peerName)
                    )
                } else {
                    Translator.translate(TranslationKey.Service.Streak.RESTORED_SELF)
                }

                TLRPC.TL_messageActionCustomAction()
                    .apply { this.message = messageText }
            }

            val tryPetInvite = petInvite@{
                if (message.message != ServiceMessage.PET_INVITE_TEXT)
                    return@petInvite null

                if (message.out) {
                    TLRPC.TL_messageActionCustomAction()
                        .apply {
                            this.message =
                                Translator.translate(TranslationKey.Service.Pet.Invite.SENT_SELF)
                        }
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

                    Translator.translate(
                        TranslationKey.Service.Pet.Invite.ACCEPTED_PEER,
                        mapOf("name" to peerName)
                    )
                } else {
                    Translator.translate(TranslationKey.Service.Pet.Invite.ACCEPTED_SELF)
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

                    Translator.translate(
                        TranslationKey.Service.Pet.Rename.PEER, mapOf(
                            "peerName" to peerName,
                            "petName" to name,
                        )
                    )
                } else {
                    Translator.translate(
                        TranslationKey.Service.Pet.Rename.SELF,
                        mapOf("petName" to name)
                    )
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
                ServiceMessage.DEATH_TEXT ->
                    Translator.translate(TranslationKey.Service.Streak.ENDED_TITLE)

                ServiceMessage.PET_INVITE_TEXT ->
                    Translator.translate(TranslationKey.Service.Pet.Invite.TITLE)

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
            val bulletinHelper = plugin.bulletinHelper
            val serviceMessagesController = plugin.serviceMessagesController
            val streakPetsController = plugin.streakPetsController

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "try to revive streak from notification"
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
                    }
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "try to accept streak-pet invitation from notification"
                    ) {
                        streaksController.setServiceMessagesEnabled(accountId, peerUserId, true)
                        serviceMessagesController.sendPetInviteAccepted(
                            accountId,
                            peerUserId
                        )

                        when (streakPetsController.create(accountId, peerUserId)) {
                            is StreakPetsController.CreateResult.Created -> {
                                plugin.syncPeerUi(accountId, peerUserId)
                                plugin.petUiManager.refreshFabForOpenChat()

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
                    param.args[0] = Translator.translate(TranslationKey.Service.Streak.ENDED_TITLE)
                    param.args[1] =
                        Translator.translate(TranslationKey.Service.Streak.ENDED_SUBTITLE)
                    param.args[3] = Translator.translate(TranslationKey.Service.Streak.ENDED_HINT)
                    param.args[5] =
                        Translator.translate(TranslationKey.Service.Streak.ENDED_ACTION)
                    param.args[9] = false
                    param.args[10] = true
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    param.args[0] =
                        Translator.translate(TranslationKey.Service.Pet.Invite.TITLE)
                    param.args[1] =
                        Translator.translate(TranslationKey.Service.Pet.Invite.DESCRIPTION)
                    param.args[3] =
                        Translator.translate(TranslationKey.Service.Pet.Invite.HINT)
                    param.args[5] =
                        Translator.translate(TranslationKey.Service.Pet.Invite.ACTION)
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

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
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

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
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

            if (prizeStars.transaction_id != ServiceMessage.DEATH_TEXT && prizeStars.transaction_id != ServiceMessage.PET_INVITE_TEXT)
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
