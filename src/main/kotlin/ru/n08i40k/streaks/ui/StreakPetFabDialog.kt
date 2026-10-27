package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.resource.ResourcesProvider
import kotlin.math.abs
import ru.n08i40k.streaks.util.dp

@SuppressLint("DiscouragedApi", "InternalInsetResource")
class StreakPetFabDialog(
    context: android.content.Context,
    val accountId: Int,
    val peerUserId: Long,
    initialState: StreakPetsController.ViewStateSnapshot,
    resourcesProvider: ResourcesProvider,
    initialSizeDp: Int,
    private val onOpenRequested: () -> Unit,
) : Dialog(context) {
    private var state = initialState
    private var destroyed = false
    private var offsetX = DEFAULT_OFFSET_X
    private var offsetY = DEFAULT_OFFSET_Y
    private var sizeDp = initialSizeDp

    private val petView = StreakPetAnimationView(context, resourcesProvider, accountId)
    private val petContainer = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val side = (minOf(measuredWidth, measuredHeight) * .60526f).toInt().coerceAtLeast(1)
            val spec = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
            petView.measure(spec, spec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val x = (width - petView.measuredWidth) / 2
            val y = ((height - petView.measuredHeight) / 2f - height * .0363f).toInt()
            petView.layout(x, y, x + petView.measuredWidth, y + petView.measuredHeight)
        }
    }.apply {
        addView(petView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        contentDescription = initialState.pet.name
        isClickable = true
        setOnClickListener { open() }
    }

    fun matches(accountId: Int, peerUserId: Long): Boolean =
        this.accountId == accountId && this.peerUserId == peerUserId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
        }

        pushState()

        setContentView(
            petContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun updateState(newState: StreakPetsController.ViewStateSnapshot) {
        state = newState
        pushState()
    }

    fun open() {
        onOpenRequested()
    }

    fun updateSizeDp(newSizeDp: Int) {
        sizeDp = newSizeDp.coerceIn(MIN_PET_FAB_SIZE_DP, MAX_PET_FAB_SIZE_DP)
    }

    fun configureWindow() {
        val size = fabSizePx()
        val bounds = resolveDragBounds(size)
        offsetX = offsetX.coerceIn(0, bounds.maxOffsetX)
        offsetY = offsetY.coerceIn(bounds.minOffsetY, bounds.maxOffsetY)

        installTouchHandling()

        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setLayout(size, size)
            setGravity(Gravity.TOP or Gravity.END)
            attributes = attributes.apply {
                x = offsetX
                y = offsetY
            }
        }
    }

    override fun dismiss() {
        if (destroyed) {
            return
        }

        super.dismiss()
        destroyed = true
        petView.release()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchHandling() {
        petContainer.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var initialX = 0
            private var initialY = 0
            private var moved = false

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val window = window ?: return false
                val attrs = window.attributes

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialX = attrs.x
                        initialY = attrs.y
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()
                        val bounds =
                            resolveDragBounds(size = v.width.takeIf { it > 0 } ?: fabSizePx())

                        if (
                            abs(dx) > dp(6)
                            || abs(dy) > dp(6)
                        ) {
                            moved = true
                            attrs.x = (initialX - dx).coerceIn(0, bounds.maxOffsetX)
                            attrs.y = (initialY + dy).coerceIn(bounds.minOffsetY, bounds.maxOffsetY)
                            offsetX = attrs.x
                            offsetY = attrs.y
                            window.attributes = attrs
                        }

                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> { moved = false; return true }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            v.performClick()
                        }
                        return true
                    }
                }

                return false
            }
        })
    }

    private fun pushState() {
        if (destroyed) return
        petContainer.contentDescription = state.pet.name
        petView.bind(state.pet.level)
    }

    private fun fabSizePx(): Int = dp(sizeDp)

    private fun resolveDragBounds(size: Int): DragBounds {
        val displayMetrics = context.resources.displayMetrics
        val statusBarHeight = resolveStatusBarHeight()
        val bottomInset = resolveBottomSystemInset()

        val maxOffsetX = (displayMetrics.widthPixels - size)
            .coerceAtLeast(0)

        val maxOffsetY = (displayMetrics.heightPixels - bottomInset - size)
            .coerceAtLeast(statusBarHeight)

        return DragBounds(
            maxOffsetX = maxOffsetX,
            minOffsetY = statusBarHeight,
            maxOffsetY = maxOffsetY
        )
    }

    private fun resolveStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId == 0) {
            return 0
        }

        return context.resources.getDimensionPixelSize(resourceId)
    }

    private fun resolveBottomSystemInset(): Int {
        val navigationBarHeightId =
            context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (navigationBarHeightId != 0) {
            return context.resources.getDimensionPixelSize(navigationBarHeightId)
        }

        val navigationBarHeightLandscapeId =
            context.resources.getIdentifier("navigation_bar_height_landscape", "dimen", "android")
        if (navigationBarHeightLandscapeId != 0) {
            return context.resources.getDimensionPixelSize(navigationBarHeightLandscapeId)
        }

        return 0
    }

    companion object {
        private const val MIN_PET_FAB_SIZE_DP = 48
        private const val MAX_PET_FAB_SIZE_DP = 128

        private const val DEFAULT_OFFSET_X = 20
        private const val DEFAULT_OFFSET_Y = 250
    }

    private data class DragBounds(
        val maxOffsetX: Int,
        val minOffsetY: Int,
        val maxOffsetY: Int,
    )
}
