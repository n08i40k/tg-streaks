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
import ru.n08i40k.streaks.controller.StreakPetsController

class StreakPetFabDialog(
    context: android.content.Context,
    initialState: StreakPetsController.UiState,
    private val onOpenRequested: () -> Unit,
) : Dialog(context) {
    private data class PetStage(
        val maxPoints: Int,
        val gradientStart: String,
        val gradientEnd: String,
        val petStart: String,
        val petEnd: String,
        val accent: String,
        val accentSecondary: String,
    )

    private val stages = listOf(
        PetStage(100, "#F9B746", "#FFF8E8", "#FFCB68", "#FF9C24", "#8D4A00", "#FFF2C8"),
        PetStage(300, "#FEA386", "#FFF2EC", "#FFC0A9", "#F9724F", "#8A2E19", "#FFE1D6"),
        PetStage(500, "#FF8EFA", "#FFF0FF", "#FFB6FC", "#FF63E3", "#842C7A", "#FFE3FB"),
        PetStage(900, "#6873FF", "#EEF0FF", "#98A1FF", "#4A56F0", "#2230A3", "#DFE3FF"),
    )

    private var state = initialState
    private var pageReady = false
    private var destroyed = false

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
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
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
                "https://tg-streaks.local/widget/",
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

    private fun buildHtml(): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                :root {
                    --bg-start: #F9B746;
                    --bg-end: #FFF8E8;
                    --accent: #8D4A00;
                    --accent-secondary: #FFF2C8;
                    --pet-start: #FFCB68;
                    --pet-end: #FF9C24;
                }

                * {
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                    user-select: none;
                }

                html, body {
                    width: 100%;
                    height: 100%;
                    margin: 0;
                    overflow: hidden;
                    background: transparent;
                }

                body {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                }

                .orb {
                    position: relative;
                    width: 76px;
                    height: 76px;
                }

                .pet {
                    position: absolute;
                    left: 50%;
                    top: 50%;
                    width: 46px;
                    height: 46px;
                    transform: translate(-50%, -56%);
                }

                .ear {
                    position: absolute;
                    top: 1px;
                    width: 12px;
                    height: 18px;
                    background: var(--pet-start);
                    clip-path: polygon(50% 0%, 100% 100%, 0% 100%);
                }

                .ear.left {
                    left: 8px;
                    transform: rotate(-14deg);
                }

                .ear.right {
                    right: 8px;
                    transform: rotate(14deg);
                }

                .body {
                    position: absolute;
                    left: 50%;
                    top: 10px;
                    width: 30px;
                    height: 28px;
                    transform: translateX(-50%);
                    border-radius: 15px 15px 13px 13px;
                    background: linear-gradient(180deg, var(--pet-start) 0%, var(--pet-end) 100%);
                    box-shadow: 0 8px 16px rgba(0,0,0,.14);
                }

                .eye {
                    position: absolute;
                    top: 10px;
                    width: 5px;
                    height: 5px;
                    border-radius: 999px;
                    background: #fff;
                }

                .eye::after {
                    content: "";
                    position: absolute;
                    left: 2px;
                    top: 2px;
                    width: 2px;
                    height: 2px;
                    border-radius: 999px;
                    background: var(--accent);
                }

                .eye.left { left: 8px; }
                .eye.right { right: 8px; }

                .mouth {
                    position: absolute;
                    left: 50%;
                    top: 17px;
                    width: 8px;
                    height: 4px;
                    transform: translateX(-50%);
                    border-bottom: 2px solid var(--accent);
                    border-radius: 0 0 8px 8px;
                }

                .flower,
                .collar,
                .sparkle,
                .crown {
                    position: absolute;
                    display: none;
                }

                .pet[data-stage="0"] .flower,
                .pet[data-stage="1"] .collar,
                .pet[data-stage="2"] .sparkle,
                .pet[data-stage="3"] .crown {
                    display: block;
                }

                .flower {
                    right: 4px;
                    top: 3px;
                    width: 12px;
                    height: 12px;
                    border-radius: 999px;
                    background: var(--accent-secondary);
                }

                .collar {
                    left: 50%;
                    top: 27px;
                    width: 18px;
                    height: 5px;
                    transform: translateX(-50%);
                    border-radius: 999px;
                    background: var(--accent);
                }

                .sparkle {
                    left: 50%;
                    top: 0;
                    width: 8px;
                    height: 8px;
                    transform: translateX(-50%) rotate(45deg);
                    background: var(--accent-secondary);
                }

                .crown {
                    left: 50%;
                    top: -1px;
                    width: 18px;
                    height: 9px;
                    transform: translateX(-50%);
                    background: var(--accent);
                    clip-path: polygon(0% 100%, 0% 48%, 18% 48%, 30% 0%, 50% 42%, 70% 0%, 82% 48%, 100% 48%, 100% 100%);
                }

            </style>
        </head>
        <body>
            <div class="orb">
                <div class="pet" id="pet" data-stage="0">
                    <div class="ear left"></div>
                    <div class="ear right"></div>
                    <div class="body">
                        <div class="eye left"></div>
                        <div class="eye right"></div>
                        <div class="mouth"></div>
                    </div>
                    <div class="flower"></div>
                    <div class="collar"></div>
                    <div class="sparkle"></div>
                    <div class="crown"></div>
                </div>
            </div>

            <script>
                window.applyState = function(state) {
                    var root = document.documentElement;
                    root.style.setProperty('--bg-start', state.gradientStart);
                    root.style.setProperty('--bg-end', state.gradientEnd);
                    root.style.setProperty('--pet-start', state.petStart);
                    root.style.setProperty('--pet-end', state.petEnd);
                    root.style.setProperty('--accent', state.accent);
                    root.style.setProperty('--accent-secondary', state.accentSecondary);
                    document.getElementById('pet').setAttribute('data-stage', String(state.stageIndex));
                };
            </script>
        </body>
        </html>
    """.trimIndent()
}
