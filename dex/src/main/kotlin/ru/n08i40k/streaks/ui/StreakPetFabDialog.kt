@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.resource.ResourcesProvider

class StreakPetFabDialog(
    context: android.content.Context,
    initialState: StreakPetsController.UiState,
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

    private val webView: WebView = createWebView()

    val touchView: View
        get() = webView

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

    fun updateState(newState: StreakPetsController.UiState) {
        state = newState
        pushState()
    }

    fun open() {
        onOpenRequested()
    }

    override fun dismiss() {
        if (destroyed) {
            return
        }

        super.dismiss()
        destroyed = true
        pageReady = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
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
}
