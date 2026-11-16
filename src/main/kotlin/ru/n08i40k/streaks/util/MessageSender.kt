package ru.n08i40k.streaks.util

import android.net.Uri
import org.telegram.messenger.AccountInstance

object MessageSender {
    fun sendText(accountId: Int, peerId: Long, message: String) {
        val account = AccountInstance.getInstance(accountId)

        `SendMessagesHelper$prepareSendingText$$12_7_0`?.let {
            it.invoke(account, message as CharSequence, peerId, false, 0, 0, 0L)
            return
        }

        `SendMessagesHelper$prepareSendingText$$12_2_0`?.let {
            it.invoke(account, message, peerId, false, 0, 0, 0L)
            return
        }

        `SendMessagesHelper$prepareSendingText$$12_1_1`?.let {
            it.invoke(account, message, peerId, false, 0, 0L)
            return
        }

        throw NoSuchMethodException("SendMessagesHelper.prepareSendingText is not available")
    }

    fun sendDocument(accountId: Int, peerId: Long, caption: String, uri: Uri) {
        val account = AccountInstance.getInstance(accountId)

        `SendMessagesHelper$prepareSendingDocuments$$12_10_0`?.let {
            it.invoke(
                account, // accountInstance
                null, // paths
                null, // originalPaths
                arrayListOf(uri), // uris
                caption, // caption
                arrayListOf<Unit>(), // entities
                null, // mime
                peerId, // dialogId
                null, // replyToMsg
                null, // replyToTopMsg
                null, // storyItem
                null, // quote
                null, // editingMessageObject
                true, // notify
                0, // scheduleDate
                0, // scheduleRepeatPeriod
                null, // inputContent
                `SendMessageChatArguments$EMPTY`, // sendMessageChatArguments
                0L, // effectId
                false, // invertMedia
                0L, // payStars
                0L, // monoForumPeerId
                null // suggestionParams
            )
            return
        }

        `SendMessagesHelper$prepareSendingDocuments$$12_2_0`?.let {
            it.invoke(
                account, // accountInstance
                null, // paths
                null, // originalPaths
                arrayListOf(uri), // uris
                caption, // caption
                arrayListOf<Unit>(), // entities
                null, // mime
                peerId, // dialogId
                null, // replyToMsg
                null, // replyToTopMsg
                null, // storyItem
                null, // quote
                null, // editingMessageObject
                true, // notify
                0, // scheduleDate
                0, // scheduleRepeatPeriod
                null, // inputContent
                null, // quickReplyShortcut
                0, // quickReplyShortcutId
                0L, // effectId
                false, // invertMedia
                0L, // payStars
                0L, // monoForumPeerId
                null // suggestionParams
            )
            return
        }

        `SendMessagesHelper$prepareSendingDocuments$$12_1_1`?.let {
            it.invoke(
                account, // accountInstance
                null, // paths
                null, // originalPaths
                arrayListOf(uri), // uris
                caption, // caption
                arrayListOf<Unit>(), // entities
                null, // mime
                peerId, // dialogId
                null, // replyToMsg
                null, // replyToTopMsg
                null, // storyItem
                null, // quote
                null, // editingMessageObject
                true, // notify
                0, // scheduleDate
                null, // inputContent
                null, // quickReplyShortcut
                0, // quickReplyShortcutId
                0L, // effectId
                false, // invertMedia
                0L, // payStars
                0L, // monoForumPeerId
                null // suggestionParams
            )
            return
        }

        throw NoSuchMethodException("SendMessagesHelper.prepareSendingDocuments is not available")
    }
}
