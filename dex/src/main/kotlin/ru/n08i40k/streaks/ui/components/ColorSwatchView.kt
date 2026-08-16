package ru.n08i40k.streaks.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import kotlin.math.min

class ColorSwatchView(context: Context) : View(context) {

    private var color: Color? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f).toFloat()
        color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon)
    }

    init {
        background = Theme.createSelectorDrawable(
            Theme.getColor(Theme.key_dialogButtonSelector),
            1,
            dp(20f)
        )
    }

    fun setColor(color: Color?) {
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - dp(4f)

        val color = this.color

        if (color != null) {
            fillPaint.color = color.toArgb()
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
        }

        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        if (color != null)
            return

        val offset = radius * 0.7071f

        canvas.drawLine(
            centerX - offset,
            centerY + offset,
            centerX + offset,
            centerY - offset,
            strokePaint
        )
    }
}
