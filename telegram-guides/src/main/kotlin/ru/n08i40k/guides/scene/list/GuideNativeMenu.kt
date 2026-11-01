package ru.n08i40k.guides.scene.list

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.vkryl.android.animator.BoolAnimator
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Adapters.DialogsAdapter
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.DialogsActivity
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.profile.GuidePeer
import ru.n08i40k.guides.profile.GuideProfiles
import ru.n08i40k.guides.util.FLAG_HAS_FROM_ID
import ru.n08i40k.guides.util.`DialogCell$currentDialogId$$setter`
import ru.n08i40k.guides.util.`DialogCell$customDialog$$setter`
import ru.n08i40k.guides.util.`DialogCell$dialogMuted$$setter`
import ru.n08i40k.guides.util.`DialogCell$drawPin$$setter`
import ru.n08i40k.guides.util.`DialogCell$unreadCount$$setter`
import ru.n08i40k.guides.util.`DialogsActivity$ViewPage$dialogsAdapter$$setter`
import ru.n08i40k.guides.util.`DialogsActivity$ViewPage$itemTouchhelper`
import ru.n08i40k.guides.util.`DialogsActivity$ViewPage$progressView`
import ru.n08i40k.guides.util.`DialogsActivity$accountPanels`
import ru.n08i40k.guides.util.`DialogsActivity$animatorFilterTabsVisible`
import ru.n08i40k.guides.util.`DialogsActivity$canShowFilterTabsView$$setter`
import ru.n08i40k.guides.util.`DialogsActivity$hasMainTabs$$setter`
import ru.n08i40k.guides.util.`DialogsActivity$viewPages`
import ru.n08i40k.guides.util.dialogsListView
import ru.n08i40k.guides.util.invokeOnPause
import ru.n08i40k.guides.util.removeAllScrollListeners

internal class GuideNativeMenu(
    private val context: Context,
    private val profiles: GuideProfiles,
) {
    private val supportsMainTabs = try {
        Class.forName("org.telegram.ui.MainTabsActivity", false, DialogsActivity::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
    private var chats: List<GuideChat> = emptyList()
    private val previewDate = (System.currentTimeMillis() / 1000).toInt()
    private var title = "Telegram"

    val dialogs: DialogsActivity = object : DialogsActivity(Bundle()) {
        override fun onFragmentCreate() =
            true

        override fun getDialogsArray(account: Int, type: Int, folder: Int, frozen: Boolean) =
            ArrayList<TLRPC.Dialog>(chats.map(::dialog))

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) = Unit

        override fun updateStoriesVisibility(animated: Boolean) = Unit

        override fun onBecomeFullyVisible() = Unit

        override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) = Unit

        override fun onResume() {
            isPaused = false
            actionBar?.onResume()
        }

        override fun onPause() {
            isPaused = true
            actionBar?.invokeOnPause()
        }

        override fun setNavigationBarColor(color: Int) = Unit

        override fun createView(context: Context): View =
            super.createView(context).also {
                installPages()
                actionBar?.setOccupyStatusBar(false)
                actionBar?.setTitle(title, null)
                hideAccountPanels()
            }
    }.apply {
        setCurrentAccount(profiles.account)
        `DialogsActivity$hasMainTabs$$setter`?.invokeExact(this as DialogsActivity, supportsMainTabs)
        storiesEnabled = false
    }

    private val tabs = if (supportsMainTabs) GuideMainTabs(dialogs, profiles.account) else null
    val main: BaseFragment
        get() = tabs?.fragment ?: dialogs

    val list
        get() = dialogs.dialogsListView

    val manager
        get() = list.layoutManager as LinearLayoutManager

    fun setTitle(value: String) {
        title = value
        dialogs.actionBar?.setTitle(value, null)
    }

    fun setChats(value: List<GuideChat>, changedIndex: Int? = null) {
        chats = value.toList()
        pages().forEach { page ->
            val list: RecyclerView = page.listView

            (list.adapter as? DialogsAdapter)?.isEmpty = chats.isEmpty()
            if (changedIndex == null)
                list.adapter?.notifyDataSetChanged()
            else
                list.adapter?.notifyItemChanged(changedIndex)
        }
        sanitizeTabs()
    }

    fun sanitize() {
        hideAccountPanels()
        sanitizeTabs()
    }

    private fun sanitizeTabs() {
        tabs?.sanitize(chats.sumOf { it.unreadCount })
    }

    private fun pages(): List<DialogsActivity.ViewPage> =
        (`DialogsActivity$viewPages`.invokeExact(dialogs) as Array<DialogsActivity.ViewPage?>?)?.filterNotNull().orEmpty()

    private fun installPages() {
        for (page in pages()) {
            val list: RecyclerView = page.listView
            val adapter = LocalAdapter().apply { isEmpty = chats.isEmpty() }

            (`DialogsActivity$ViewPage$itemTouchhelper`.invokeExact(page) as ItemTouchHelper?)?.attachToRecyclerView(null)
            list.removeAllScrollListeners()
            `DialogsActivity$ViewPage$dialogsAdapter$$setter`.invokeExact(page, adapter as DialogsAdapter)
            list.adapter = adapter
            (`DialogsActivity$ViewPage$progressView`.invokeExact(page) as View?)?.visibility = View.GONE
        }
    }

    private fun hideAccountPanels() {
        dialogs.hasStories = false
        dialogs.storiesEnabled = false
        dialogs.dialogStoriesCellVisible = false
        dialogs.progressToShowStories = 0f
        dialogs.progressToDialogStoriesCell = 0f
        dialogs.dialogStoriesCell?.visibility = View.GONE
        `DialogsActivity$canShowFilterTabsView$$setter`.invokeExact(dialogs, false)
        `DialogsActivity$animatorFilterTabsVisible`?.let {
            (it.invokeExact(dialogs) as BoolAnimator).setValue(false, false)
        }
        for (getter in `DialogsActivity$accountPanels`)
            (getter.invokeExact(dialogs) as View?)?.visibility = View.GONE
    }

    private fun dialog(chat: GuideChat): TLRPC.Dialog =
        TLRPC.TL_dialog().apply {
            id = chat.id
            unread_count = chat.unreadCount
            pinned = chat.pinned
            last_message_date = previewDate
        }

    private inner class LocalAdapter : DialogsAdapter(
        dialogs, context, 0, 0, false, arrayListOf(), profiles.account, null
    ) {
        override fun getItemCount() =
            chats.size

        override fun getCurrentCount() =
            chats.size

        override fun getDialogsCount() =
            chats.size

        override fun getItem(position: Int): Any? =
            chats.getOrNull(position)?.let(::dialog)

        override fun getItemId(position: Int) =
            chats[position].id

        override fun getItemViewType(position: Int) =
            0

        override fun getItemHeight(position: Int) =
            AndroidUtilities.dp(72f)

        override fun fixPosition(position: Int) =
            position

        override fun findDialogPosition(id: Long) =
            chats.indexOfFirst { it.id == id }

        override fun getDialogsListIsFrozen() =
            false

        override fun isDataSetChanged() =
            false

        override fun fixScrollGap(
            list: RecyclerListView,
            position: Int,
            offset: Int,
            hasArchive: Boolean,
            hasStories: Boolean,
            scroll: Boolean,
            animated: Boolean
        ) =
            0

        override fun isEnabled(holder: RecyclerView.ViewHolder) =
            false

        override fun updateList(callback: Runnable?) {
            callback?.run()
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) = Unit

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            LocalHolder(LocalCell())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as LocalHolder).local.bind(chats[position], position)
        }
    }

    private inner class LocalHolder(val local: LocalCell) : RecyclerView.ViewHolder(local.view)

    private inner class LocalCell {
        val view = DialogCell(null, context, false, false, profiles.account, null).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun bind(chat: GuideChat, position: Int) {
            view.useSeparator = position != chats.lastIndex

            val peer = profiles.peer(chat)
            val date = previewDate - position * 300

            if (peer != null)
                bindProfile(chat, peer, position, date)
            else
                bindLocal(chat, position, date)
        }

        private fun bindProfile(chat: GuideChat, peer: GuidePeer, position: Int, date: Int) {
            `DialogCell$customDialog$$setter`.invokeExact(view, null as DialogCell.CustomDialog?)

            val target = when (val entity = peer.entity) {
                is TLRPC.User -> TLRPC.TL_peerUser().apply { user_id = entity.id }
                is TLRPC.Chat ->
                    if (ChatObject.isChannel(entity))
                        TLRPC.TL_peerChannel().apply { channel_id = entity.id }
                    else
                        TLRPC.TL_peerChat().apply { chat_id = entity.id }
                else -> error("Unsupported guide peer: ${entity.javaClass.name}")
            }
            val source = TLRPC.TL_message().apply {
                id = -position - 1
                this.date = date
                message = chat.message
                dialog_id = peer.dialogId
                out = chat.outgoing
                unread = false
                peer_id = target
                from_id =
                    if (chat.outgoing) TLRPC.TL_peerUser().apply {
                        user_id = UserConfig.getInstance(profiles.account).clientUserId
                    }
                    else target
                flags = FLAG_HAS_FROM_ID
                media = TLRPC.TL_messageMediaEmpty()
            }
            val preview = object : MessageObject(profiles.account, source, true, false) {
                override fun isOutOwner() =
                    chat.outgoing

                override fun isOut() =
                    chat.outgoing
            }

            view.setDialog(peer.dialogId, preview, date, false, false)
            `DialogCell$unreadCount$$setter`.invokeExact(view, chat.unreadCount)
            `DialogCell$drawPin$$setter`.invokeExact(view, chat.pinned)
            `DialogCell$dialogMuted$$setter`.invokeExact(view, chat.muted)

            if (view.measuredWidth > 0)
                view.buildLayout()
        }

        private fun bindLocal(chat: GuideChat, position: Int, date: Int) {
            `DialogCell$currentDialogId$$setter`.invokeExact(view, 0L)
            view.setDialog(DialogCell.CustomDialog().apply {
                id = position + 1
                name = chat.name
                message = chat.message
                this.date = date
                unread_count = chat.unreadCount
                pinned = chat.pinned
                muted = chat.muted

                sent = if (chat.outgoing) 2 else -1
            })
        }
    }
}
