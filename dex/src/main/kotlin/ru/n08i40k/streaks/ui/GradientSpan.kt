package ru.n08i40k.streaks.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableString
import android.text.style.ReplacementSpan


class GradientSpan(private val colors: IntArray) : ReplacementSpan() {
    companion object {
        fun fromString(text: String, colors: IntArray): SpannableString =
            buildSpanned(text, listOf(Triple("%g[", "]") { listOf(GradientSpan(colors)) }))
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        val saved = paint.shader

        paint.shader = LinearGradient(x, 0f, x + width, 0f, colors, null, Shader.TileMode.CLAMP)
        canvas.drawText(text!!, start, end, x, y.toFloat(), paint)

        paint.shader = saved
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            val paintMetrics = paint.fontMetricsInt
            it.ascent = paintMetrics.ascent
            it.descent = paintMetrics.descent
            it.top = paintMetrics.top
            it.bottom = paintMetrics.bottom
        }

        return paint.measureText(text, start, end).toInt()
    }
}