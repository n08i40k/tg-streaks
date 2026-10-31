package ru.n08i40k.guides.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal suspend fun animateGuide(duration: Long, interpolator: TimeInterpolator, frame: (Float) -> Unit) {
    frame(0f)

    ValueAnimator.ofFloat(0f, 1f).apply {
        this.duration = duration
        this.interpolator = interpolator
        addUpdateListener { frame(it.animatedValue as Float) }
    }.await()
}

internal suspend fun Animator.await() =
    suspendCancellableCoroutine { continuation ->
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (continuation.isActive)
                    continuation.resume(Unit)
            }
        })
        continuation.invokeOnCancellation { cancel() }

        if (!isStarted)
            start()
    }
