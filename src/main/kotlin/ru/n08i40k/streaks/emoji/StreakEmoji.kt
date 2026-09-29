package ru.n08i40k.streaks.emoji

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AnimatedEmojiDrawable
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.util.runOnMainThread
import kotlin.math.ceil

class StreakEmoji(parentView: View, val size: Int) :
    AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(parentView, size) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = size * 0.6f
    }

    // отступ между эмодзи и числом
    private val gap = size / 8

    private var peerUserId: Long = 0
    private var cachedStreakViewData: StreakViewData? = null

    private var lengthText: String? = null
    private var lengthTextWidth = 0

    private fun clearStreakView() {
        set(null as Drawable?, false)
    }

    private fun applyStreak(streakViewData: StreakViewData?) {
        if (streakViewData == null) {
            clearStreakView()
            invalidateSelf()
            return
        }

        set(streakViewData.documentId, false)
        setParticles(true, false)
        color = streakViewData.accentColor.toArgb()

        invalidateSelf()
    }

    fun setPeerUserId(peerUserId: Long, clearStreak: Boolean = false): Boolean {
        this.peerUserId = peerUserId

        val user = peerUserId
            .takeIf { it != 0L && !clearStreak }
            ?.let { MessagesController.getInstance(UserConfig.selectedAccount).getUserOrChat(it) }
                as? TLRPC.User

        cachedStreakViewData = user?.let {
            Plugin.getInstance()
                .streaksController
                .getViewData(UserConfig.selectedAccount, it.id)
        }

        lengthText = cachedStreakViewData
            ?.length
            ?.toString()

        lengthTextWidth = lengthText
            ?.let { gap + ceil(textPaint.measureText(it)).toInt() }
            ?: 0

        applyStreak(cachedStreakViewData)

        runOnMainThread(this@StreakEmoji::invalidateSelf)

        return cachedStreakViewData != null
    }

    fun getPeerUserId(): Long = peerUserId

    fun hasStreak(): Boolean = cachedStreakViewData != null

    fun getTextWidth(): Int = lengthTextWidth

    // квадрат эмодзи плюс число справа от него
    fun getTotalWidth(): Int = size + lengthTextWidth

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val streakViewData = cachedStreakViewData ?: return
        val text = lengthText ?: return

        textPaint.color = streakViewData.accentColor.toArgb()

        // текст выравнивается по вертикальному центру эмодзи
        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 1.5F

        canvas.drawText(
            text,
            bounds.right.toFloat() + gap,
            baseline,
            textPaint
        )
    }

    override fun getMinimumWidth(): Int =
        super.getMinimumWidth() + getTextWidth()

    override fun getIntrinsicWidth(): Int =
        super.getIntrinsicWidth() + getTextWidth()
}
