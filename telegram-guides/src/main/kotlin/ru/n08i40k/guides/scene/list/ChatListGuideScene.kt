package ru.n08i40k.guides.scene.list

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.profile.GuideProfiles
import ru.n08i40k.guides.scene.GuideFragmentHost
import ru.n08i40k.guides.scene.GuideViewportScene
import ru.n08i40k.guides.util.awaitGuideLayout

@SuppressLint("ViewConstructor")
internal class ChatListGuideScene(context: Context, profiles: GuideProfiles) : GuideViewportScene(context) {
    private var chats = emptyList<GuideChat>()
    private var highlightedId: Long? = null
    private val native = GuideNativeMenu(context, profiles)
    private val host = GuideFragmentHost(context)

    private val list
        get() = native.list

    private val manager
        get() = native.manager

    private val sanitizeBeforeDraw = ViewTreeObserver.OnPreDrawListener {
        if (!disposed)
            native.sanitize()

        true
    }

    init {
        addContent(host.view)
    }

    override fun onFirstShow() {
        host.show(native.main)
        screen.viewTreeObserver.addOnPreDrawListener(sanitizeBeforeDraw)
        list.addItemDecoration(HighlightDecoration())
        updateTheme()
    }

    override suspend fun perform(action: GuideAction) {
        when (action) {
            is GuideAction.SetTitle -> native.setTitle(action.title)

            is GuideAction.ShowChats -> {
                chats = action.chats.toList()
                highlightedId = null
                native.setChats(chats)
                manager.scrollToPositionWithOffset(0, 0)
                awaitGuideLayout(list)
            }

            is GuideAction.UpdateChat -> {
                replace(action.chat.id) { action.chat }
                awaitGuideLayout(list)
            }

            is GuideAction.SetBadge -> replace(action.id) { it.copy(badge = action.badge) }

            is GuideAction.HighlightChat -> {
                highlightedId = action.id
                list.invalidate()
            }

            is GuideAction.ScrollToChat -> {
                manager.scrollToPositionWithOffset(indexOf(action.id), 0)
                awaitGuideLayout(list)
            }

            is GuideAction.TapChat -> {
                val index = indexOf(action.id)
                val visible = list.findViewHolderForAdapterPosition(index)?.itemView

                if (visible == null || visible.top < 0 || visible.bottom > list.height) {
                    manager.scrollToPositionWithOffset(index, 0)
                    awaitGuideLayout(list)
                }

                val row = checkNotNull(list.findViewHolderForAdapterPosition(index)).itemView

                tap(row, PointF(row.width * .55f, row.height * .5f), press = true)
            }

            is GuideAction.TapAt -> tapAt(action.x, action.y)

            else -> error("${action.javaClass.simpleName} is not a chat list action")
        }
    }

    private fun indexOf(id: Long): Int =
        chats.indexOfFirst { it.id == id }.also { check(it >= 0) { "Unknown guide chat $id" } }

    private fun replace(id: Long, update: (GuideChat) -> GuideChat) {
        val index = indexOf(id)

        chats = chats.toMutableList().apply { set(index, update(chats[index])) }
        native.setChats(chats, index)
    }

    override fun updateTheme() {
        if (!shown || disposed)
            return

        for (fragment in listOf(native.main, native.dialogs))
            fragment.themeDescriptions?.forEach { it.setColor(Theme.getColor(it.currentKey), false, false) }

        native.setChats(chats)
        host.view.invalidate()
        invalidateTouch()
    }

    override fun onDispose() {
        if (screen.viewTreeObserver.isAlive)
            screen.viewTreeObserver.removeOnPreDrawListener(sanitizeBeforeDraw)

        host.dispose()
    }

    private inner class HighlightDecoration : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val index = chats.indexOfFirst { it.id == highlightedId }

            if (index < 0)
                return

            val row = parent.findViewHolderForAdapterPosition(index)?.itemView ?: return
            val color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
            val top = row.y
            val bottom = row.y + row.height

            paint.color = Theme.multAlpha(color, .12f)
            canvas.drawRect(row.left.toFloat(), top, row.right.toFloat(), bottom, paint)
            paint.color = color
            canvas.drawRect(row.left.toFloat(), top, row.left + AndroidUtilities.dp(3f).toFloat(), bottom, paint)
        }
    }
}
