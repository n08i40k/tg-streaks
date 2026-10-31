package ru.n08i40k.streaks.util

import android.util.DisplayMetrics
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.n08i40k.guides.util.awaitGuideLayout
import kotlin.coroutines.resume

suspend fun RecyclerView.smoothScrollToTop(position: Int) {
    awaitGuideLayout(this)

    val layoutManager = checkNotNull(layoutManager)

    suspendCancellableCoroutine { continuation ->
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE)
                    return

                view.removeOnScrollListener(this)

                if (continuation.isActive)
                    continuation.resume(Unit)
            }
        }
        val scroller = object : LinearSmoothScroller(context) {
            override fun calculateSpeedPerPixel(metrics: DisplayMetrics) =
                220f / metrics.densityDpi

            override fun getVerticalSnapPreference() =
                SNAP_TO_START
        }

        scroller.targetPosition = position
        addOnScrollListener(listener)
        layoutManager.startSmoothScroll(scroller)

        post {
            if (!scroller.isRunning && scrollState == RecyclerView.SCROLL_STATE_IDLE && continuation.isActive) {
                removeOnScrollListener(listener)
                continuation.resume(Unit)
            }
        }

        continuation.invokeOnCancellation {
            removeOnScrollListener(listener)
            stopScroll()
        }
    }
}
