package ru.n08i40k.streaks.util

import android.net.Uri
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ChatActivity

object MessageSender {
    @Suppress("EnumEntryName")
    private enum class SendMessagesHelperRev {
        Pre_12_2_0,
        Pre_12_7_0,
        Latest;
    }

    private val currentRevision by lazy {
        if (isClientVersionBelow("12.2.0"))
            return@lazy SendMessagesHelperRev.Pre_12_2_0

        if (isClientVersionBelow("12.7.0"))
            return@lazy SendMessagesHelperRev.Pre_12_7_0

        return@lazy SendMessagesHelperRev.Latest
    }

    private val prepareSendingText by lazy {
        when (currentRevision) {
            SendMessagesHelperRev.Pre_12_2_0 ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    String::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Long::class.java
                )

            SendMessagesHelperRev.Pre_12_7_0 ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    String::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Long::class.java
                )

            SendMessagesHelperRev.Latest ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    CharSequence::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Long::class.java
                )
        }
    }

    private val prepareSendingDocuments by lazy {
        when (currentRevision) {
            SendMessagesHelperRev.Pre_12_2_0 ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingDocuments",
                    AccountInstance::class.java,
                    ArrayList::class.java,
                    ArrayList::class.java,
                    ArrayList::class.java,
                    String::class.java,
                    ArrayList::class.java,
                    String::class.java,
                    Long::class.java,
                    MessageObject::class.java,
                    MessageObject::class.java,
                    TL_stories.StoryItem::class.java,
                    ChatActivity.ReplyQuote::class.java,
                    MessageObject::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    InputContentInfoCompat::class.java,
                    String::class.java,
                    Int::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    Long::class.java,
                    MessageSuggestionParams::class.java
                )

            SendMessagesHelperRev.Pre_12_7_0,
            SendMessagesHelperRev.Latest ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingDocuments",
                    AccountInstance::class.java,
                    ArrayList::class.java,
                    ArrayList::class.java,
                    ArrayList::class.java,
                    String::class.java,
                    ArrayList::class.java,
                    String::class.java,
                    Long::class.java,
                    MessageObject::class.java,
                    MessageObject::class.java,
                    TL_stories.StoryItem::class.java,
                    ChatActivity.ReplyQuote::class.java,
                    MessageObject::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    InputContentInfoCompat::class.java,
                    String::class.java,
                    Int::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    Long::class.java,
                    MessageSuggestionParams::class.java
                )
        }
    }

    fun send(accountId: Int, peerId: Long, message: String) {
        val account = AccountInstance.getInstance(accountId)

        when (currentRevision) {
            SendMessagesHelperRev.Pre_12_2_0 ->
                prepareSendingText.invoke(null, account, message, peerId, false, 0, 0L)

            SendMessagesHelperRev.Pre_12_7_0 ->
                prepareSendingText.invoke(null, account, message, peerId, false, 0, 0, 0L)

            SendMessagesHelperRev.Latest ->
                prepareSendingText.invoke(
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
            SendMessagesHelperRev.Pre_12_2_0 ->
                prepareSendingDocuments.invoke(
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

            SendMessagesHelperRev.Pre_12_7_0,
            SendMessagesHelperRev.Latest ->
                prepareSendingDocuments.invoke(
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
        }
    }
}