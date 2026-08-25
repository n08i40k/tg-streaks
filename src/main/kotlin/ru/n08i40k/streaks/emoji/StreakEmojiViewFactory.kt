package ru.n08i40k.streaks.emoji

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import ru.n08i40k.badges.compat.BadgesViewFactory
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.getLastFragment

class StreakEmojiViewFactory : BadgesViewFactory {
    @SuppressLint("ViewConstructor")
    private class BadgeView(parent: View, heightPx: Int) : View(parent.context) {
        private val streakEmoji = StreakEmoji(parent, heightPx)

        init {
            // view вне иерархии, onAttachedToWindow не придёт никогда
            streakEmoji.attach()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                resolveSize(streakEmoji.getTotalWidth(), widthMeasureSpec),
                resolveSize(streakEmoji.size, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)

            // эмодзи занимает квадрат по левому краю, число рисуется за его границей
            val emojiTop = (height - streakEmoji.size) / 2

            streakEmoji.setBounds(0, emojiTop, streakEmoji.size, emojiTop + streakEmoji.size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            streakEmoji.draw(canvas)
        }

        // SDK передаёт нам событие в наших координатах, но клик по detached view
        // через стандартный OnClickListener не сработает: post() уходит в очередь,
        // которая проигрывается только после попадания в иерархию
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!streakEmoji.hasStreak())
                return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true

                MotionEvent.ACTION_UP -> {
                    if (contains(event.x, event.y))
                        Logger.tryOrFatal("handle streak badge click", ::showStreakInfo)

                    return true
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> return true
            }

            return false
        }

        private fun contains(x: Float, y: Float): Boolean =
            x >= 0f && x <= width && y >= 0f && y <= height

        private fun showStreakInfo() {
            val accountId = UserConfig.selectedAccount

            val user = MessagesController.getInstance(accountId)
                .getUser(streakEmoji.getPeerUserId())
                ?: return

            val streakViewData = Plugin.getInstance()
                .streaksController
                .getViewData(accountId, user.id)
                ?: return

            val fragment = getLastFragment()
                ?: return

            fragment.showDialog(
                StreakInfoBottomSheet(
                    fragment,
                    accountId,
                    user,
                    fragment.resourceProvider,
                    streakViewData
                )
            )
        }

        fun bind(userId: Long): Boolean {
            val visible = streakEmoji.setPeerUserId(userId)

            // ширина зависит от длины стрика, а SDK меряет view после bind
            requestLayout()

            return visible
        }

        fun destroy() = streakEmoji.detach()
    }

    override fun create(parent: View, heightPx: Int): View = BadgeView(parent, heightPx)

    override fun bind(view: View, userId: Long): Boolean =
        (view as? BadgeView)?.bind(userId) ?: false

    override fun destroy(view: View) {
        (view as? BadgeView)?.destroy()
    }
}
