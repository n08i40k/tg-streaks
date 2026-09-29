package ru.n08i40k.streaks.util

import android.net.Uri
import org.telegram.messenger.AccountInstance

object MessageSender {
    @Suppress("EnumEntryName")
    private enum class Revision {
        Pre_12_2_0,
        Pre_12_7_0,
        Pre_12_10_0,
        Latest;
    }

    private val currentRevision =
        if (isClientVersionBelow("12.2.0"))
            Revision.Pre_12_2_0
        else if (isClientVersionBelow("12.7.0"))
            Revision.Pre_12_7_0
        else if (isClientVersionBelow("12.10.0"))
            Revision.Pre_12_10_0
        else
            Revision.Latest

    fun sendText(accountId: Int, peerId: Long, message: String) {
        val account = AccountInstance.getInstance(accountId)

        when (currentRevision) {
            Revision.Pre_12_2_0 ->
                `SendMessagesHelper$prepareSendingText`.invoke(
                    null,
                    account,
                    message,
                    peerId,
                    false,
                    0,
                    0L
                )

            Revision.Pre_12_7_0 ->
                `SendMessagesHelper$prepareSendingText`.invoke(
                    null,
                    account,
                    message,
                    peerId,
                    false,
                    0,
                    0,
                    0L
                )

            Revision.Pre_12_10_0,
            Revision.Latest ->
                `SendMessagesHelper$prepareSendingText`.invoke(
                    null,
                    account,
                    message as CharSequence,
                    peerId,
                    false,
                    0,
                    0,
                    0L
                )
        }
    }

    fun sendDocument(accountId: Int, peerId: Long, caption: String, uri: Uri) {
        val account = AccountInstance.getInstance(accountId)

        when (currentRevision) {
            Revision.Pre_12_2_0 ->
                `SendMessagesHelper$prepareSendingDocuments`.invoke(
                    null,
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
                    0, // effectId
                    false, // invertMedia
                    0, // payStars
                    0, // monoForumPeerId
                    null // suggestionParams
                )

            Revision.Pre_12_7_0,
            Revision.Pre_12_10_0 ->
                `SendMessagesHelper$prepareSendingDocuments`.invoke(
                    null,
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
                    0, // effectId
                    false, // invertMedia
                    0, // payStars
                    0, // monoForumPeerId
                    null // suggestionParams
                )

            Revision.Latest ->
                `SendMessagesHelper$prepareSendingDocuments`.invoke(
                    null,
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
                    0, // effectId
                    false, // invertMedia
                    0, // payStars
                    0, // monoForumPeerId
                    null // suggestionParams
                )
        }
    }
}