package ru.n08i40k.guides.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import kotlin.coroutines.resume

internal class GuideTouchOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var targetX = 0f
    private var targetY = 0f

    suspend fun tap(x: Float, y: Float) =
        suspendCancellableCoroutine { continuation ->
            targetX = x
            targetY = y

            val animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500

                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        animator = null
                        invalidate()

                        if (continuation.isActive)
                            continuation.resume(Unit)
                    }
                })
            }

            animator = animation
            continuation.invokeOnCancellation { animation.cancel() }
            animation.start()
        }

    override fun onDraw(canvas: Canvas) {
        if (animator == null)
            return

        val color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AndroidUtilities.dp(2f).toFloat()
        paint.color = Theme.multAlpha(color, (1f - progress) * .8f)

        canvas.drawCircle(
            targetX,
            targetY,
            AndroidUtilities.dp(12f + 30f * progress).toFloat(),
            paint
        )
    }

    fun dispose() {
        animator?.cancel()
    }
}
