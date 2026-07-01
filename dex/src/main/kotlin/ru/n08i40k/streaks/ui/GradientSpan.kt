package ru.n08i40k.streaks.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan


class GradientSpan(private val colors: IntArray) : ReplacementSpan() {
    companion object {
        val REGEX = Regex("%g\\[([^\\[\\]%]*)]", setOf(RegexOption.MULTILINE))

        fun fromString(text: String, colors: IntArray): SpannableString {
            val ranges = arrayListOf<IntRange>()

            val spannable = SpannableString(
                text.replace(REGEX) { match ->
                    val group = match.groups[1]!!
                    ranges.add(group.range)

                    group.value
                }
            )

            for (i in 0..<ranges.size) {
                val range = ranges[i]

                spannable.setSpan(
                    GradientSpan(colors),
                    range.first - 3 * (i + 1) - i,
                    range.last - 3 * (i + 1) - i + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            return spannable
        }
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
    ): Int = paint.measureText(text, start, end).toInt()
}