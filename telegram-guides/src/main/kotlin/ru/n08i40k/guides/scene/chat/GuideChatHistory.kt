package ru.n08i40k.guides.scene.chat

import android.util.SparseArray
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.validateGuideMessages
import ru.n08i40k.guides.util.awaitGuideLayout
import ru.n08i40k.guides.util.FLAG_HAS_FROM_ID
import ru.n08i40k.guides.util.`ChatActivity$cacheEndReached`
import ru.n08i40k.guides.util.`ChatActivity$chatAdapter`
import ru.n08i40k.guides.util.`ChatActivity$chatListView`
import ru.n08i40k.guides.util.`ChatActivity$currentPinnedMessageId$$setter`
import ru.n08i40k.guides.util.`ChatActivity$endReached`
import ru.n08i40k.guides.util.`ChatActivity$firstLoading$$setter`
import ru.n08i40k.guides.util.`ChatActivity$firstMessagesLoaded$$setter`
import ru.n08i40k.guides.util.`ChatActivity$forwardEndReached`
import ru.n08i40k.guides.util.`ChatActivity$getStableIdForDateObject`
import ru.n08i40k.guides.util.`ChatActivity$loading$$setter`
import ru.n08i40k.guides.util.`ChatActivity$loadingForward$$setter`
import ru.n08i40k.guides.util.`ChatActivity$messagesByDays`
import ru.n08i40k.guides.util.`ChatActivity$messagesByDaysSorted`
import ru.n08i40k.guides.util.`ChatActivity$messagesDict`
import ru.n08i40k.guides.util.`ChatActivity$paused`
import ru.n08i40k.guides.util.`ChatActivity$paused$$setter`
import ru.n08i40k.guides.util.`ChatActivity$pinnedEndReached$$setter`
import ru.n08i40k.guides.util.`ChatActivity$pinnedMessageIds`
import ru.n08i40k.guides.util.`ChatActivity$pinnedMessageObjects`
import ru.n08i40k.guides.util.`ChatActivity$processNewMessages`
import java.util.Calendar
import kotlin.coroutines.resume

internal class GuideChatHistory(
    private val chat: ChatActivity,
    private val dialogId: Long,
    messages: List<GuideMessage>,
    private val presentation: GuidePresentation?,
) {
    private var messages = messages.toList()
    private var installed = false

    private val list: RecyclerView?
        get() = `ChatActivity$chatListView`.invokeExact(chat) as RecyclerView?

    private val adapter: ChatActivity.ChatActivityAdapter?
        get() = `ChatActivity$chatAdapter`.invokeExact(chat) as ChatActivity.ChatActivityAdapter?

    init {
        validateGuideMessages(messages)
    }

    fun install() {
        `ChatActivity$paused$$setter`.invokeExact(chat, true)
        `ChatActivity$loading$$setter`.invokeExact(chat, false)
        `ChatActivity$loadingForward$$setter`.invokeExact(chat, false)
        `ChatActivity$firstLoading$$setter`.invokeExact(chat, false)
        `ChatActivity$firstMessagesLoaded$$setter`.invokeExact(chat, true)
        (`ChatActivity$pinnedMessageIds`.invokeExact(chat) as ArrayList<Int>).clear()
        (`ChatActivity$pinnedMessageObjects`.invokeExact(chat) as HashMap<Int, MessageObject>).clear()
        `ChatActivity$currentPinnedMessageId$$setter`.invokeExact(chat, 0)
        `ChatActivity$pinnedEndReached$$setter`.invokeExact(chat, true)

        for (getter in listOf(`ChatActivity$endReached`, `ChatActivity$cacheEndReached`, `ChatActivity$forwardEndReached`))
            (getter.invokeExact(chat) as BooleanArray).fill(true)

        chat.fragmentOpened = true
        chat.openAnimationEnded = true
        installed = true
        rebuild()
    }

    fun show() =
        replace(messages)

    fun replace(messages: List<GuideMessage>) {
        validateGuideMessages(messages)
        this.messages = messages.toList()

        if (!installed)
            return

        rebuild()
        adapter?.apply {
            updateRowsSafe()
            notifyDataSetChanged()
        }
        list?.scrollToPosition(0)
    }

    suspend fun append(message: GuideMessage) {
        check(installed)

        val next = messages + message

        validateGuideMessages(next)

        val local = createMessage(message)
        val paused = `ChatActivity$paused`.invokeExact(chat) as Boolean

        try {
            `ChatActivity$paused$$setter`.invokeExact(chat, false)
            `ChatActivity$processNewMessages`.invokeExact(chat, arrayListOf(local))
        } finally {
            `ChatActivity$paused$$setter`.invokeExact(chat, paused)
        }

        check(chat.messages.any { it === local }) { "Telegram did not insert guide message ${message.id}" }
        messages = next
        chat.scrollToLastMessage(false, false)

        val list = checkNotNull(list)

        awaitGuideLayout(list)

        val animator = list.itemAnimator ?: return

        suspendCancellableCoroutine { continuation ->
            animator.isRunning {
                if (continuation.isActive)
                    continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { animator.endAnimations() }
        }
    }

    suspend fun messageView(id: Int): View {
        val list = checkNotNull(list)

        awaitGuideLayout(list)
        visibleRow(list, id)?.let { return it }

        val index = chat.messages.indexOfFirst { it.id == -id }

        check(index >= 0) { "Unknown guide message $id" }
        list.scrollToPosition(checkNotNull(adapter).messagesStartRow + index)
        awaitGuideLayout(list)

        return checkNotNull(visibleRow(list, id)) { "Guide message $id is not visible" }
    }

    private fun visibleRow(list: RecyclerView, id: Int): View? =
        (0 until list.childCount).map(list::getChildAt).firstOrNull {
            val message = when (it) {
                is ChatMessageCell -> it.messageObject
                is ChatActionCell -> it.messageObject
                else -> null
            }

            message?.id == -id
        }

    private fun createMessage(item: GuideMessage): MessageObject {
        val account = chat.currentAccount
        val self = UserConfig.getInstance(account).clientUserId
        val targetChat = if (dialogId < 0) MessagesController.getInstance(account).getChat(-dialogId) else null

        fun targetPeer(): TLRPC.Peer =
            when {
                dialogId > 0 -> TLRPC.TL_peerUser().apply { user_id = dialogId }
                ChatObject.isChannel(targetChat) -> TLRPC.TL_peerChannel().apply { channel_id = -dialogId }
                else -> TLRPC.TL_peerChat().apply { chat_id = -dialogId }
            }

        val source = TLRPC.TL_message().apply {
            id = -item.id
            date = item.date
            message = item.text
            out = item.outgoing
            unread = false
            media_unread = false
            dialog_id = dialogId
            post = targetChat?.broadcast == true
            from_id =
                if (item.outgoing && !post) TLRPC.TL_peerUser().apply { user_id = self }
                else targetPeer()
            peer_id = targetPeer()
            flags = FLAG_HAS_FROM_ID
            media = TLRPC.TL_messageMediaEmpty()
        }

        item.content?.let { return checkNotNull(presentation).message(account, source, it) }

        return object : MessageObject(account, source, true, false) {
            override fun isOutOwner() =
                item.outgoing

            override fun isOut() =
                item.outgoing
        }
    }

    private fun rebuild() {
        val account = chat.currentAccount
        val dict = `ChatActivity$messagesDict`.invokeExact(chat) as Array<SparseArray<MessageObject>>
        val byDays = `ChatActivity$messagesByDays`.invokeExact(chat) as HashMap<String, ArrayList<MessageObject>>
        val sortedDays = `ChatActivity$messagesByDaysSorted`.invokeExact(chat) as SparseArray<ArrayList<MessageObject>>

        chat.messages.clear()
        dict.forEach { it.clear() }
        byDays.clear()
        sortedDays.clear()

        val local = messages.map { createMessage(it).apply { stableId = ChatActivity.lastStableId++ } }

        for (day in local.groupBy { it.dateKey }.values.reversed()) {
            val first = day.first()
            val ordered = ArrayList(day.asReversed())

            byDays[first.dateKey] = ordered
            sortedDays.put(first.dateKeyInt, ordered)

            for (message in ordered) {
                chat.messages.add(message)
                dict[0].put(message.id, message)
            }

            chat.messages.add(dateHeader(account, first))
        }
    }

    private fun dateHeader(account: Int, first: MessageObject): MessageObject {
        val date = first.messageOwner.date
        val midnight = Calendar.getInstance().apply {
            timeInMillis = date * 1000L
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val source = TLRPC.TL_message().apply {
            this.date = (midnight.timeInMillis / 1000).toInt()
            message = LocaleController.formatDateChat(date.toLong())
        }

        return MessageObject(account, source, false, false).apply {
            type = 10
            contentType = 1
            isDateObject = true
            stableId = `ChatActivity$getStableIdForDateObject`.invokeExact(chat, first.dateKeyInt) as Int
        }
    }
}
