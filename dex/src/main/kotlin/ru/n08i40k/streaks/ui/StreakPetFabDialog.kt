@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.resource.ResourcesProvider
import kotlin.math.abs

class StreakPetFabDialog(
    context: android.content.Context,
    initialState: StreakPetsController.ViewStateSnapshot,
    private val resourcesProvider: ResourcesProvider,
    private val onOpenRequested: () -> Unit,
) : Dialog(context) {
    private val stages = Plugin.getInstance().streakPetLevelRegistry.levels().ifEmpty {
        throw IllegalStateException("Streak pet levels are not registered")
    }

    private var state = initialState
    private var pageReady = false
    private var destroyed = false
    private var lastPushedStateJson: String? = null
    private var offsetX = DEFAULT_OFFSET_X
    private var offsetY = DEFAULT_OFFSET_Y

    private val webView: WebView = createWebView()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        setContentView(
            webView,
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
        pageReady = false
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.onPause()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        webView.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            resumeTimers()
            onResume()
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    pushState()
                }
            }

            loadDataWithBaseURL(
                StreakPetUiResources.loadFabBaseUrl(resourcesProvider),
                buildHtml(),
                "text/html",
                "utf-8",
                null
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchHandling() {
        webView.setOnTouchListener(object : View.OnTouchListener {
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
                        val bounds = resolveDragBounds(size = v.width.takeIf { it > 0 } ?: fabSizePx())

                        if (
                            abs(dx) > AndroidUtilities.dp(6f)
                            || abs(dy) > AndroidUtilities.dp(6f)
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

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            open()
                        }
                        return true
                    }
                }

                return false
            }
        })
    }

    private fun pushState() {
        if (!pageReady || destroyed) {
            return
        }

        val json = buildStateJson()
        if (json == lastPushedStateJson) {
            return
        }

        lastPushedStateJson = json
        webView.evaluateJavascript(
            "window.applyState(JSON.parse(${JSONObject.quote(json)}));",
            null
        )
    }

    private fun buildStateJson(): String {
        val stageIndex = resolveUnlockedIndex(state.pet.points)
        val stage = stages[stageIndex]

        return JSONObject().apply {
            put("stageIndex", stageIndex)
            put("mediaSrc", stage.imageResourcePath)
            put("gradientStart", stage.gradientStart)
            put("gradientEnd", stage.gradientEnd)
            put("petStart", stage.petStart)
            put("petEnd", stage.petEnd)
            put("accent", stage.accent)
            put("accentSecondary", stage.accentSecondary)
        }.toString()
    }

    private fun resolveUnlockedIndex(points: Int): Int {
        var unlocked = 0

        stages.forEachIndexed { index, stage ->
            if (points >= stage.maxPoints && index < stages.lastIndex) {
                unlocked = index + 1
            }
        }

        return unlocked
    }

    private fun buildHtml(): String =
        StreakPetUiResources.loadFabHtml(resourcesProvider)

    private fun fabSizePx(): Int = AndroidUtilities.dp(76f)

    private fun resolveDragBounds(size: Int): DragBounds {
        val displayMetrics = context.resources.displayMetrics
        val statusBarHeight = resolveStatusBarHeight()
        val bottomInset = resolveBottomSystemInset()
        val maxOffsetX = (displayMetrics.widthPixels - size).coerceAtLeast(0)
        val minOffsetY = statusBarHeight
        val maxOffsetY = (displayMetrics.heightPixels - bottomInset - size).coerceAtLeast(minOffsetY)

        return DragBounds(
            maxOffsetX = maxOffsetX,
            minOffsetY = minOffsetY,
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
        private const val DEFAULT_OFFSET_X = 20
        private const val DEFAULT_OFFSET_Y = 250
    }

    private data class DragBounds(
        val maxOffsetX: Int,
        val minOffsetY: Int,
        val maxOffsetY: Int,
    )
}
