package ru.n08i40k.guides.player

import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import org.telegram.ui.Components.CubicBezierInterpolator
import ru.n08i40k.guides.scene.GuideLayer
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.guides.util.GuideCleanup
import ru.n08i40k.guides.util.animateGuide
import ru.n08i40k.guides.util.awaitGuideLayout

internal class GuideSceneHost(private val container: FrameLayout) {
    private val stack = mutableListOf<GuideScene>()
    private var transitioning = false
    private var disposed = false

    val current: GuideScene?
        get() = stack.lastOrNull()

    val root: GuideScene
        get() = checkNotNull(stack.firstOrNull()) { "No guide scene is active" }

    fun setRoot(scene: GuideScene) {
        check(!disposed && stack.isEmpty() && !transitioning)
        attach(scene)
    }

    private fun attach(scene: GuideScene) {
        stack.add(scene)

        try {
            container.addView(scene.getView(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            scene.onShow()
        } catch (error: Throwable) {
            stack.remove(scene)

            try {
                release(scene)
            } catch (cleanup: Throwable) {
                if (cleanup !== error)
                    error.addSuppressed(cleanup)
            }

            throw error
        }
    }

    suspend fun open(scene: GuideScene) {
        check(!disposed && !transitioning)
        val previous = checkNotNull(current)

        transitioning = true

        try {
            scene.getView().alpha = 0f
            attach(scene)
            if (scene.layer == GuideLayer.PAGE)
                animate(previous.getView(), scene.getView(), forward = true)
            else
                showOverlay(scene, opening = true)
        } finally {
            transitioning = false

            if (!disposed) {
                if (scene.layer == GuideLayer.PAGE)
                    previous.getView().visibility = View.GONE

                reset(previous.getView())
                reset(scene.getView())
            }
        }
    }

    suspend fun back() {
        check(!disposed && !transitioning && stack.size > 1)
        val previous = stack.last()
        val destination = stack[stack.lastIndex - 1]

        transitioning = true

        try {
            destination.getView().visibility = View.VISIBLE
            if (previous.layer == GuideLayer.PAGE)
                animate(previous.getView(), destination.getView(), forward = false)
            else
                showOverlay(previous, opening = false)
        } finally {
            transitioning = false

            if (!disposed) {
                reset(destination.getView())
                stack.removeAt(stack.lastIndex)
                release(previous)
            }
        }
    }

    suspend fun backToRoot(prepare: suspend (GuideScene) -> Unit = {}) {
        check(!disposed && !transitioning)
        val destination = root
        val previous = stack.last()

        if (destination === previous) {
            prepare(destination)

            return
        }

        transitioning = true

        try {
            destination.getView().apply {
                alpha = 0f
                visibility = View.VISIBLE
            }
            prepare(destination)
            animate(previous.getView(), destination.getView(), forward = false)
        } finally {
            transitioning = false

            if (!disposed) {
                reset(destination.getView())
                val removed = stack.drop(1).asReversed()
                stack.subList(1, stack.size).clear()
                releaseAll(removed)
            }
        }
    }

    private suspend fun showOverlay(scene: GuideScene, opening: Boolean) {
        awaitGuideLayout(container)
        scene.getView().bringToFront()
        scene.transition(opening)
    }

    private suspend fun animate(previous: View, destination: View, forward: Boolean) {
        awaitGuideLayout(container)

        val width = container.width.toFloat()

        if (width <= 0)
            return

        val foreground = if (forward) destination else previous
        val elevation = foreground.elevation

        foreground.bringToFront()
        foreground.elevation = maxOf(previous.elevation, destination.elevation) + 1f

        try {
            animateGuide(PAGE_DURATION, CubicBezierInterpolator.EASE_OUT_QUINT) { progress ->
                if (forward) {
                    destination.translationX = width * (1f - progress)
                    destination.alpha = 1f
                    previous.translationX = -width * .25f * progress
                    previous.alpha = 1f - .15f * progress
                } else {
                    previous.translationX = width * progress
                    destination.translationX = -width * .25f * (1f - progress)
                    destination.alpha = .85f + .15f * progress
                }
            }
        } finally {
            foreground.elevation = elevation
        }
    }

    private fun reset(view: View) {
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }

    private fun release(scene: GuideScene) {
        try {
            scene.dispose()
        } finally {
            container.removeView(scene.getView())
        }
    }

    private fun releaseAll(scenes: List<GuideScene>) {
        GuideCleanup.run(*scenes.map { scene -> Runnable { release(scene) } }.toTypedArray())
    }

    fun updateTheme() =
        stack.forEach { it.updateTheme() }

    fun dispose() {
        if (disposed)
            return

        disposed = true

        val removed = stack.toList().asReversed()

        stack.clear()
        releaseAll(removed)
    }

    private companion object {
        const val PAGE_DURATION = 280L
    }
}
