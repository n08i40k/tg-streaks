package ru.n08i40k.streaks.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.telegram.messenger.AndroidUtilities.dp

class LinearProgressView(context: Context) : View(context) {
    companion object {
        private const val INDETERMINATE_DURATION = 1400L
        private const val INDETERMINATE_SEGMENT_FRACTION = 0.3f
        private const val PROGRESS_ANIMATION_DURATION = 300L
    }

    var indicatorColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var trackColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    var trackThickness: Int = dp(4f)
        set(value) {
            field = value
            requestLayout()
        }

    var max: Int = 100

    var isIndeterminate: Boolean = false
        set(value) {
            if (field == value) return

            field = value

            if (value) startIndeterminateAnimation()
            else stopIndeterminateAnimation()
        }

    private var animatedProgressFraction: Float = 0f
    private var progressAnimator: ValueAnimator? = null

    private var indeterminatePhase: Float = 0f
    private var indeterminateAnimator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)

        if (visibility == VISIBLE && isIndeterminate) startIndeterminateAnimation()
        else stopIndeterminateAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        stopIndeterminateAnimation()
        progressAnimator?.cancel()
    }

    private fun startIndeterminateAnimation() {
        if (indeterminateAnimator != null) return

        indeterminateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INDETERMINATE_DURATION
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                indeterminatePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopIndeterminateAnimation() {
        indeterminateAnimator?.cancel()
        indeterminateAnimator = null
    }

    fun setProgressCompat(value: Int, animated: Boolean) {
        isIndeterminate = false

        val target = if (max == 0) 0f else value.coerceIn(0, max).toFloat() / max

        progressAnimator?.cancel()

        if (animated) {
            progressAnimator = ValueAnimator.ofFloat(animatedProgressFraction, target).apply {
                duration = PROGRESS_ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedProgressFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animatedProgressFraction = target
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(trackThickness, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) return

        val radius = h / 2f

        if (trackColor != Color.TRANSPARENT) {
            trackPaint.color = trackColor
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)
        }

        indicatorPaint.color = indicatorColor

        if (isIndeterminate) {
            drawIndeterminateSegment(canvas, indeterminatePhase, w, h, radius)
            drawIndeterminateSegment(canvas, (indeterminatePhase + 0.5f) % 1f, w, h, radius)
        } else {
            val progressWidth = w * animatedProgressFraction

            if (progressWidth > 0f) canvas.drawRoundRect(
                0f,
                0f,
                progressWidth,
                h,
                radius,
                radius,
                indicatorPaint
            )
        }
    }

    private fun drawIndeterminateSegment(
        canvas: Canvas,
        phase: Float,
        w: Float,
        h: Float,
        radius: Float
    ) {
        val start =
            ((phase * (1f + INDETERMINATE_SEGMENT_FRACTION) - INDETERMINATE_SEGMENT_FRACTION) * w)
                .coerceIn(0f, w)
        val end = (start + INDETERMINATE_SEGMENT_FRACTION * w).coerceIn(0f, w)

        if (end > start) canvas.drawRoundRect(start, 0f, end, h, radius, radius, indicatorPaint)
    }
}
