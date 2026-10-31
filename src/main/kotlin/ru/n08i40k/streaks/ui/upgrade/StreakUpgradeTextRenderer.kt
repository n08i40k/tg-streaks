package ru.n08i40k.streaks.ui.upgrade

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.animation.PathInterpolator
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.upgrade.Easing.hump
import ru.n08i40k.streaks.ui.upgrade.Easing.smooth
import ru.n08i40k.streaks.ui.upgrade.UpgradeTimeline as T
import ru.n08i40k.streaks.util.colorBlend
import ru.n08i40k.streaks.util.dp
import kotlin.math.sin

internal class StreakUpgradeTextRenderer(
    private val fromDays: Int,
    private val toDays: Int,
    private val fromColor: Int,
    private val toColor: Int,
    private val palette: StreakUpgradePalette,
    numberTypeface: Typeface?,
) {
    private companion object {
        const val NUMBER_SIZE_DP = 76
        const val TRACKING_DP = 2
        const val TITLE_Y_DP = -117
        const val NUMBER_Y_DP = 157
        const val UNIT_Y_DP = 184
        const val BAR_Y_DP = 202
        const val BAR_HALF_WIDTH_DP = 52

        val PULSE_FADE_IN = Span(0f, .06f)
        val PULSE_FADE_OUT = Span(.72f, .28f)
    }

    private val title = TrackedText(Strings.upgrade_scene_title())
    private val unit = TrackedText(Strings.upgrade_scene_day_unit())

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = numberTypeface ?: Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = dp(NUMBER_SIZE_DP).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barGrowth = PathInterpolator(.22f, 0f, .2f, 1f)
    private val pulseGrowth = PathInterpolator(.4f, 0f, .2f, 1f)
    private val pulseColor = colorBlend(toColor, Color.WHITE, .7f)
    private val pulseGradient = LinearGradient(
        -dp(30).toFloat(), 0f, dp(14).toFloat(), 0f,
        intArrayOf(Color.TRANSPARENT, pulseColor, pulseColor, Color.TRANSPARENT),
        floatArrayOf(0f, .45f, .7f, 1f), Shader.TileMode.CLAMP,
    )
    private val numberGradient = LinearGradient(
        0f, -dp(72).toFloat(), 0f, dp(10).toFloat(),
        intArrayOf(
            if (palette.isDark) Color.WHITE else readable(toColor, .75f),
            readable(toColor, if (palette.isDark) .75f else .3f),
            toColor,
        ),
        null, Shader.TileMode.CLAMP,
    )
    private val sheen = LinearGradient(
        -dp(35).toFloat(), 0f, dp(35).toFloat(), 0f,
        intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
    )
    private val shaderMatrix = Matrix()

    private fun readable(color: Int, amount: Float) =
        colorBlend(color, palette.ink, if (palette.isDark) amount else amount * .6f)

    fun draw(canvas: Canvas, cx: Float, cy: Float, availableWidth: Float, time: Float) {
        val upgraded = T.isUpgraded(time)
        val color = if (upgraded) toColor else fromColor
        val departure = smooth(T.OLD_TEXT_OUT.at(time))
        val alpha =
            if (upgraded) smooth(T.NEW_TEXT_IN.at(time))
            else smooth(T.OLD_TEXT_IN.at(time)) * (1 - departure)

        val steadyAlpha = smooth(T.OLD_TEXT_IN.at(time))
        val sceneColor = colorBlend(fromColor, toColor, smooth(T.COLOR_SHIFT.at(time)))

        if (upgraded)
            drawTitle(canvas, cx, cy + dp(TITLE_Y_DP), availableWidth, time, alpha)
        drawNumber(canvas, cx, cy, availableWidth, sceneColor, time, steadyAlpha)

        labelPaint.textSize = dp(11).toFloat()
        labelPaint.color = readable(sceneColor, .6f)
        labelPaint.alpha = (steadyAlpha * 230).toInt()
        unit.draw(canvas, labelPaint, cx, cy + dp(UNIT_Y_DP), dp(TRACKING_DP).toFloat())

        drawBar(canvas, cx, cy + dp(BAR_Y_DP), color, time, upgraded, alpha, departure)
    }

    private fun drawTitle(canvas: Canvas, cx: Float, y: Float, availableWidth: Float, time: Float, alpha: Float) {
        labelPaint.textSize = dp(12).toFloat()
        labelPaint.color = readable(toColor, .55f)
        labelPaint.setShadowLayer(dp(8).toFloat(), 0f, 0f, toColor)
        val spread = 1 - smooth(T.TITLE_SPREAD.at(time))
        val tracking = dp(TRACKING_DP) + dp(4) * spread
        val fit = minOf(1f, (availableWidth - dp(32)).coerceAtLeast(1f) / title.width(labelPaint, tracking))

        canvas.save()
        canvas.translate(cx, y)
        canvas.scale(fit, fit)
        title.draw(canvas, labelPaint, 0f, 0f, tracking) { index ->
            val progress = smooth(T.TITLE_LETTER.at(time - index * T.TITLE_LETTER_STAGGER))
            labelPaint.alpha = (255 * alpha * progress).toInt()
            dp(12) * (1 - progress)
        }
        labelPaint.clearShadowLayer()
        canvas.restore()
    }

    private fun countProgress(time: Float) = T.COUNTER.at(time).let { it * it }

    fun countValue(time: Float) = fromDays + ((toDays - fromDays) * countProgress(time)).toInt()

    private fun drawNumber(
        canvas: Canvas, cx: Float, cy: Float, availableWidth: Float,
        color: Int, time: Float, alpha: Float,
    ) {
        val value = countValue(time)
        val text = value.toString()
        val charge = smooth(T.OLD_CHARGE.at(time))
        val upgraded = T.isUpgraded(time)
        val squeeze = 1 - .14f * charge
        val release = if (upgraded) .14f * Easing.spring(T.NEW_TEXT_ARRIVAL.at(time), 5f, 9f) else 0f
        val tremble = if (upgraded) 0f else charge * dp(2.5f)
        val restyle = smooth(T.NUMBER_RESTYLE.at(time))
        val width = numberPaint.measureText(text)
        val fit = minOf(1f, (availableWidth - dp(40)).coerceAtLeast(1f) / width)

        canvas.save()
        canvas.translate(
            cx + tremble * Easing.noise(time * 47f + 5f),
            cy + dp(NUMBER_Y_DP) + tremble * Easing.noise(time * 52f + 13f),
        )
        val scale = (squeeze + release) * fit
        canvas.scale(scale, scale)

        numberPaint.shader = null
        numberPaint.color = color
        numberPaint.alpha = (alpha * 100).toInt()
        numberPaint.setShadowLayer(dp(18).toFloat(), 0f, 0f, color)
        numberPaint.style = Paint.Style.STROKE
        numberPaint.strokeWidth = dp(2).toFloat()
        canvas.drawText(text, 0f, 0f, numberPaint)
        numberPaint.clearShadowLayer()

        numberPaint.style = Paint.Style.FILL
        if (restyle < 1f) {
            numberPaint.color = readable(color, .65f)
            numberPaint.alpha = (alpha * 255 * (1 - restyle)).toInt()
            canvas.drawText(text, 0f, 0f, numberPaint)
        }
        if (restyle > 0f) {
            numberPaint.color = Color.WHITE
            numberPaint.shader = numberGradient
            numberPaint.alpha = (alpha * 255 * restyle).toInt()
            canvas.drawText(text, 0f, 0f, numberPaint)
        }

        if (upgraded && T.SHEEN.isActive(time)) {
            val sweep = T.SHEEN.at(time)
            val halfWidth = width / 2 + dp(40)
            shaderMatrix.setTranslate(-halfWidth + 2 * halfWidth * sweep, 0f)
            sheen.setLocalMatrix(shaderMatrix)
            numberPaint.shader = sheen
            numberPaint.alpha = (hump(sweep) * 255).toInt()
            canvas.drawText(text, 0f, 0f, numberPaint)
        }
        numberPaint.shader = null
        canvas.restore()
    }

    private fun drawBar(
        canvas: Canvas, cx: Float, y: Float,
        color: Int, time: Float, upgraded: Boolean, alpha: Float, departure: Float,
    ) {
        val growth = if (upgraded) barGrowth.getInterpolation(T.BAR_GROWTH.at(time)) else 1 - departure
        val halfLine = dp(BAR_HALF_WIDTH_DP) * growth
        val halfHeight = dp(2).toFloat()
        barPaint.color = color
        barPaint.alpha = (alpha * 150).toInt()
        canvas.drawRoundRect(cx - halfLine, y - halfHeight, cx + halfLine, y + halfHeight, halfHeight, halfHeight, barPaint)

        if (!upgraded || !T.BAR_PULSE.isActive(time))
            return

        val pulse = T.BAR_PULSE.at(time)
        val travel = (halfLine + dp(30)) * pulseGrowth.getInterpolation(pulse)
        val opacity = smooth(PULSE_FADE_IN.at(pulse)) * (1 - smooth(PULSE_FADE_OUT.at(pulse)))
        shaderMatrix.setTranslate(travel, 0f)
        pulseGradient.setLocalMatrix(shaderMatrix)
        barPaint.shader = pulseGradient
        barPaint.alpha = (opacity * 255).toInt()
        for (direction in floatArrayOf(1f, -1f)) {
            canvas.save()
            canvas.translate(cx, y)
            canvas.scale(direction, 1f)
            canvas.clipRect(0f, -halfHeight, halfLine, halfHeight)
            canvas.drawRoundRect(-halfLine, -halfHeight, halfLine, halfHeight, halfHeight, halfHeight, barPaint)
            canvas.restore()
        }
        barPaint.shader = null
    }

    private class TrackedText(text: String) {
        private val glyphs = text.map(Char::toString)
        private val advances = FloatArray(glyphs.size)
        private var measuredSize = -1f
        private var measuredTypeface: Typeface? = null

        private fun measure(paint: Paint) {
            if (paint.textSize == measuredSize && paint.typeface === measuredTypeface)
                return
            glyphs.forEachIndexed { index, glyph -> advances[index] = paint.measureText(glyph) }
            measuredSize = paint.textSize
            measuredTypeface = paint.typeface
        }

        fun width(paint: Paint, tracking: Float): Float {
            measure(paint)
            return (advances.sum() + tracking * (glyphs.size - 1)).coerceAtLeast(1f)
        }

        inline fun draw(
            canvas: Canvas, paint: Paint, cx: Float, y: Float, tracking: Float,
            offsetY: (index: Int) -> Float = { 0f },
        ) {
            var x = cx - width(paint, tracking) / 2
            glyphs.forEachIndexed { index, glyph ->
                canvas.drawText(glyph, x, y + offsetY(index), paint)
                x += advances[index] + tracking
            }
        }
    }
}
