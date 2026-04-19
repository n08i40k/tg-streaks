@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

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
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getFieldValue
import java.util.AbstractMap

class ServiceMessagesHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // каким блять хуем я не могу кастануть child в parent?
        // это кстати хук MessageObject для полной замены вида сервисных сообщений
        @Suppress("CAST_NEVER_SUCCEEDS")
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
            val translator = Plugin.getInstance().translator

            val message = param.args[1] as? TLRPC.Message
                ?: return@before

            val currentAccount = param.args[0] as? Int ?: 0

            if (message.message == null)
                return@before

            val tryStreakCreate = streakCreate@{
                if (message.message != ServiceMessage.CREATE_TEXT)
                    return@streakCreate null

                TLRPC.TL_messageActionCustomAction().apply {
                    val messageText =
                        translator.translate(TranslationKey.Service.Streak.STARTED_TEXT)
                    (this as TLRPC.MessageAction).message = messageText
                } as TLRPC.MessageAction
            }

            val tryStreakUpgrade = streakUpgrade@{
                val days = ServiceMessage.UPGRADE_REGEX
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@streakUpgrade null

                TLRPC.TL_messageActionCustomAction().apply {
                    val messageText =
                        translator.translate(TranslationKey.Service.Streak.LEVEL_UP_TEXT)
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
                        translator.translate(TranslationKey.Service.Streak.RESTORED_SELF)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.Service.Streak.RESTORED_PEER)
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
                            translator.translate(TranslationKey.Service.Pet.Invite.SENT_SELF)
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
                        translator.translate(TranslationKey.Service.Pet.Invite.ACCEPTED_SELF)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.Service.Pet.Invite.ACCEPTED_PEER)
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
                        translator.translate(TranslationKey.Service.Pet.Rename.SELF)
                            .replace("{petName}", name)
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translator.translate(TranslationKey.Service.Pet.Rename.PEER)
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

        // Текст короткого сообщения в списке чатов
        @Suppress("CAST_NEVER_SUCCEEDS")
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

            val translator = Plugin.getInstance().translator

            thisObject.messageText = when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT ->
                    translator.translate(TranslationKey.Service.Streak.ENDED_TITLE)

                ServiceMessage.PET_INVITE_TEXT ->
                    translator.translate(TranslationKey.Service.Pet.Invite.TITLE)

                else -> return@after
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

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            val accountId = UserConfig.selectedAccount
            val peerUserId = messageObject.dialogId

            val plugin = Plugin.getInstance()

            val accountTaskRunnerRegistry = plugin.accountTaskRunnerRegistry
            val streaksController = plugin.streaksController
            val bulletinHelper = plugin.bulletinHelper
            val serviceMessagesController = plugin.serviceMessagesController
            val streakPetsController = plugin.streakPetsController

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    accountTaskRunnerRegistry.enqueue(
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
                    accountTaskRunnerRegistry.enqueue(
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

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                ?: return@before

            val translator = Plugin.getInstance().translator

            when (prizeStars.transaction_id) {
                ServiceMessage.DEATH_TEXT -> {
                    param.args[0] = translator.translate(TranslationKey.Service.Streak.ENDED_TITLE)
                    param.args[1] =
                        translator.translate(TranslationKey.Service.Streak.ENDED_SUBTITLE)
                    param.args[3] = translator.translate(TranslationKey.Service.Streak.ENDED_HINT)
                    param.args[5] =
                        translator.translate(TranslationKey.Service.Streak.ENDED_ACTION)
                    param.args[9] = false
                    param.args[10] = true
                }

                ServiceMessage.PET_INVITE_TEXT -> {
                    param.args[0] =
                        translator.translate(TranslationKey.Service.Pet.Invite.TITLE)
                    param.args[1] =
                        translator.translate(TranslationKey.Service.Pet.Invite.DESCRIPTION)
                    param.args[3] =
                        translator.translate(TranslationKey.Service.Pet.Invite.HINT)
                    param.args[5] =
                        translator.translate(TranslationKey.Service.Pet.Invite.ACTION)
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

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
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

            val prizeStars = messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
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
