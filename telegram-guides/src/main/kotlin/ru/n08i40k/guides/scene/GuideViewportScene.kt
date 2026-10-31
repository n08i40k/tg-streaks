package ru.n08i40k.guides.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import ru.n08i40k.guides.ui.GuideTouchOverlay
import ru.n08i40k.guides.util.GuideCleanup
import ru.n08i40k.guides.util.awaitGuideLayout
import ru.n08i40k.guides.util.centerPoint
import ru.n08i40k.guides.util.guidePointIn
import ru.n08i40k.guides.util.measureGuideViewport

@SuppressLint("ViewConstructor")
abstract class GuideViewportScene(context: Context) : FrameLayout(context), GuideScene {
    protected val screen = FrameLayout(context)
    private val touch = GuideTouchOverlay(context)

    protected var shown = false
        private set

    protected var disposed = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        screen.addView(touch, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(screen)
    }

    protected abstract fun onFirstShow()

    protected open fun onDispose() = Unit

    final override fun onShow() {
        check(!disposed)

        if (shown)
            return

        shown = true
        onFirstShow()
    }

    final override fun dispose() {
        if (disposed)
            return

        disposed = true
        GuideCleanup.run({ touch.dispose() }, ::onDispose, { screen.removeAllViews() })
    }

    protected fun addContent(
        view: View,
        params: LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
    ) =
        screen.addView(view, screen.indexOfChild(touch), params)

    protected suspend fun tap(view: View, point: PointF = view.centerPoint(), press: Boolean = false) {
        awaitGuideLayout(screen)

        val position = guidePointIn(view, screen, point)

        if (!press)
            return touch.tap(position.x, position.y)

        view.drawableHotspotChanged(point.x, point.y)
        view.isPressed = true

        try {
            touch.tap(position.x, position.y)
        } finally {
            view.isPressed = false
        }
    }

    protected suspend fun tapAt(x: Float, y: Float) =
        touch.tap(screen.width * x, screen.height * y)

    protected fun invalidateTouch() =
        touch.invalidate()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(width, height)
        measureGuideViewport(screen, width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) =
        screen.layout(0, 0, screen.measuredWidth, screen.measuredHeight)

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent) =
        true

    override fun dispatchGenericMotionEvent(event: MotionEvent) =
        true

    override fun getView(): View =
        this
}
