package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.UiThread
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.resource.ResourcesProvider

@UiThread
class StreakPetFabWebView(resourcesProvider: ResourcesProvider) {
    private val contextWrapper = MutableContextWrapper(ApplicationLoader.applicationContext)

    private var pageReady = false
    private var attached = false
    private var destroyed = false
    private var pendingStateJson: String? = null
    private var lastPushedStateJson: String? = null

    val webView: WebView = createWebView(resourcesProvider)

    fun attach(context: Context) {
        if (destroyed || attached) {
            return
        }

        attached = true
        contextWrapper.baseContext = context
        webView.resumeTimers()
        webView.onResume()

        if (pageReady) {
            flushState()
            webView.evaluateJavascript("window.resumeMedia();", null)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun detach() {
        if (destroyed || !attached) {
            return
        }

        attached = false
        webView.setOnTouchListener(null)
        (webView.parent as? ViewGroup)?.removeView(webView)

        if (pageReady) {
            webView.evaluateJavascript("window.suspendMedia();", null)
        }

        webView.onPause()
        contextWrapper.baseContext = ApplicationLoader.applicationContext
    }

    fun pushState(stateJson: String) {
        pendingStateJson = stateJson
        flushState()
    }

    fun destroy() {
        if (destroyed) {
            return
        }

        detach()
        destroyed = true
        pageReady = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
    }

    private fun flushState() {
        if (!pageReady || !attached || destroyed) {
            return
        }

        val json = pendingStateJson ?: return
        pendingStateJson = null

        if (json == lastPushedStateJson) {
            return
        }

        lastPushedStateJson = json
        webView.evaluateJavascript(
            "window.applyState(JSON.parse(${JSONObject.quote(json)}));",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(resourcesProvider: ResourcesProvider): WebView {
        return WebView(contextWrapper).apply {
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
                    if (destroyed) {
                        return
                    }

                    pageReady = true

                    if (attached) {
                        flushState()
                        webView.evaluateJavascript("window.resumeMedia();", null)
                    } else {
                        webView.evaluateJavascript("window.suspendMedia();", null)
                    }
                }
            }

            loadDataWithBaseURL(
                StreakPetUiResources.loadFabBaseUrl(resourcesProvider),
                StreakPetUiResources.loadFabHtml(resourcesProvider),
                "text/html",
                "utf-8",
                null
            )

            onPause()
        }
    }
}
