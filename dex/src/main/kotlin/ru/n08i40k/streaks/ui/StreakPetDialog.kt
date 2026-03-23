@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.data.StreakPetTaskPayload
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.util.Translator
import java.io.ByteArrayOutputStream
import java.io.File

class StreakPetDialog(
    private val fragment: BaseFragment,
    initialState: StreakPetsController.UiState,
    private val translator: Translator,
    private val onRenameRequested: (String) -> Unit,
    private val onDismissed: (() -> Unit)? = null,
) : Dialog(
    fragment.context ?: fragment.parentActivity
    ?: throw IllegalStateException("Cannot create streak pet dialog without a context"),
    android.R.style.Theme_Translucent_NoTitleBar_Fullscreen
) {
    private data class PetStage(
        val maxPoints: Int,
        val gradientStart: String,
        val gradientEnd: String,
        val petStart: String,
        val petEnd: String,
        val accent: String,
        val accentSecondary: String,
    )

    private data class TaskUi(
        val title: String,
        val reward: Int,
        val target: Int,
        val ownerProgress: Int,
        val peerProgress: Int,
        val isCompleted: Boolean,
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

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.18f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView?.setPadding(0, 0, 0, 0)
        }

        webView = createWebView()
        setContentView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onStart() {
        super.onStart()

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView?.setPadding(0, 0, 0, 0)
        }
    }

    fun updateState(newState: StreakPetsController.UiState) {
        state = newState
        pushState()
    }

    override fun dismiss() {
        if (destroyed) {
            return
        }

        super.dismiss()
        destroyWebView()
        onDismissed?.invoke()
    }

    private fun destroyWebView() {
        if (destroyed || !::webView.isInitialized) {
            return
        }

        destroyed = true
        pageReady = false

        webView.removeJavascriptInterface("AndroidBridge")
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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            addJavascriptInterface(Bridge(), "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    log(
                        "console/${consoleMessage.messageLevel().name.lowercase()}: " +
                            "${consoleMessage.message()} " +
                            "@ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    log("page finished: ${url ?: "unknown"}")
                    pushState()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        log(
                            "page error: ${error?.description ?: "unknown"} " +
                                "@ ${request.url}"
                        )
                    }
                }
            }

            loadDataWithBaseURL(
                "https://tg-streaks.local/",
                buildHtml(AndroidUtilities.getStatusBarHeight(context)),
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun pushState() {
        if (!pageReady || destroyed || !::webView.isInitialized) {
            return
        }

        val json = buildStateJson()
        log(
            "push state: streakDays=${state.streak?.length ?: 0}, " +
                "points=${state.pet.points}, tasks=${state.tasks.size}"
        )
        webView.evaluateJavascript(
            "window.applyState(JSON.parse(${JSONObject.quote(json)}));",
            null
        )
    }

    private fun log(message: String) {
        runCatching {
            Plugin.getInstance().logger.info("[PetSheet] $message")
        }
    }

    private fun buildStateJson(): String {
        val objectJson = JSONObject()

        objectJson.put("streakDays", state.streak?.length ?: 0)
        objectJson.put("points", state.pet.points)
        objectJson.put("petName", state.pet.name)
        objectJson.put("ownerInitial", resolveInitial(state.ownerUser, "Y"))
        objectJson.put("peerInitial", resolveInitial(state.peerUser, "S"))
        objectJson.put("ownerAvatarSrc", resolveAvatarDataUri(state.ownerUser))
        objectJson.put("peerAvatarSrc", resolveAvatarDataUri(state.peerUser))
        objectJson.put("preferredStageIndex", resolveUnlockedIndex(state.pet.points))
        objectJson.put("badges", JSONArray(listOf(3, 10, 30, 100, 200)))

        objectJson.put(
            "texts",
            JSONObject().apply {
                put("streakDays", t(TranslationKey.PET_SHEET_STREAK_DAYS))
                put("locked", t(TranslationKey.PET_SHEET_LOCKED))
                put("lockedSubtext", t(TranslationKey.PET_SHEET_LOCKED_SUBTEXT))
                put("tasksTitle", t(TranslationKey.PET_SHEET_TASKS_TITLE))
                put("badgesTitle", t(TranslationKey.PET_SHEET_BADGES_TITLE))
                put("progressYou", t(TranslationKey.PET_SHEET_PROGRESS_YOU))
                put("progressPeer", t(TranslationKey.PET_SHEET_PROGRESS_PEER))
                put("renameTitle", t(TranslationKey.PET_SHEET_RENAME_TITLE))
                put("renameHint", t(TranslationKey.PET_SHEET_RENAME_HINT))
                put("renameSave", t(TranslationKey.PET_SHEET_RENAME_SAVE))
                put("renameCancel", t(TranslationKey.PET_SHEET_RENAME_CANCEL))
                put("maxLevel", t(TranslationKey.PET_SHEET_MAX_LEVEL))
                put(
                    "pointsToEvolution",
                    translator.translate(
                        TranslationKey.PET_SHEET_POINTS_TO_EVOLUTION,
                        mapOf("count" to "{count}")
                    )
                )
            }
        )

        objectJson.put(
            "stages",
            JSONArray().apply {
                stages.forEach { stage ->
                    put(
                        JSONObject().apply {
                            put("maxPoints", stage.maxPoints)
                            put("gradientStart", stage.gradientStart)
                            put("gradientEnd", stage.gradientEnd)
                            put("petStart", stage.petStart)
                            put("petEnd", stage.petEnd)
                            put("accent", stage.accent)
                            put("accentSecondary", stage.accentSecondary)
                        }
                    )
                }
            }
        )

        objectJson.put(
            "tasks",
            JSONArray().apply {
                state.tasks.forEach { task ->
                    put(task.toJson())
                }
            }
        )

        return objectJson.toString()
    }

    private fun StreakPetTask.toJson(): JSONObject {
        val task = toTaskUi()
        return JSONObject().apply {
            put("title", task.title)
            put("reward", task.reward)
            put("target", task.target)
            put("ownerProgress", task.ownerProgress)
            put("peerProgress", task.peerProgress)
            put("completed", task.isCompleted)
        }
    }

    private fun StreakPetTask.toTaskUi(): TaskUi {
        return when (val payload = payload) {
            is StreakPetTaskPayload.ExchangeOneMessage -> TaskUi(
                title = t(TranslationKey.PET_SHEET_TASK_EXCHANGE_ONE_MESSAGE),
                reward = type.points,
                target = 1,
                ownerProgress = if (payload.fromOwnerMessageId != null) 1 else 0,
                peerProgress = if (payload.fromPeerMessageId != null) 1 else 0,
                isCompleted = isCompleted,
            )

            is StreakPetTaskPayload.SendFourMessagesEach -> TaskUi(
                title = t(TranslationKey.PET_SHEET_TASK_SEND_FOUR_MESSAGES_EACH),
                reward = type.points,
                target = 4,
                ownerProgress = payload.fromOwnerMessagesCount,
                peerProgress = payload.fromPeerMessagesCount,
                isCompleted = isCompleted,
            )

            is StreakPetTaskPayload.SendTenMessagesEach -> TaskUi(
                title = t(TranslationKey.PET_SHEET_TASK_SEND_TEN_MESSAGES_EACH),
                reward = type.points,
                target = 10,
                ownerProgress = payload.fromOwnerMessagesCount,
                peerProgress = payload.fromPeerMessagesCount,
                isCompleted = isCompleted,
            )
        }
    }

    private fun resolveInitial(user: TLRPC.User?, fallback: String): String {
        val base = user?.label
            ?.removePrefix("@")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallback

        return base.first().uppercase()
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

    private fun resolveAvatarDataUri(user: TLRPC.User?): String? {
        if (user == null) {
            return null
        }

        return try {
            val imageLocation = ImageLocation.getForUser(user, ImageLocation.TYPE_SMALL) ?: return null
            val file = FileLoader.getInstance(fragment.currentAccount).getLocalFile(imageLocation)
            if (file != null && file.isFile) {
                val mime = file.toMimeType()
                val payload = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                return "data:$mime;base64,$payload"
            }

            user.photo?.strippedBitmap?.bitmap?.toDataUri()
        } catch (_: Throwable) {
            null
        }
    }

    private fun File.toMimeType(): String {
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun Bitmap.toDataUri(): String? {
        return try {
            ByteArrayOutputStream().use { output ->
                if (!compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    return null
                }

                val payload = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                "data:image/png;base64,$payload"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun t(key: String): String = translator.translate(key)

    private fun buildHtml(safeTopPx: Int): String = """
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
                    --safe-top: ${safeTopPx}px;
                }

                * {
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                }

                html, body {
                    margin: 0;
                    min-height: 100%;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                    color: #111827;
                    background: linear-gradient(180deg, var(--bg-start) 0%, var(--bg-end) 100%);
                }

                body {
                    padding-top: calc(var(--safe-top) + 12px);
                    overflow-y: auto;
                }

                .shell {
                    width: 100%;
                    min-height: 100vh;
                    padding: 0 0 28px;
                }

                .app {
                    width: 100vw;
                    max-width: none;
                    padding: 0 8px;
                }

                .topbar {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    margin-bottom: 18px;
                }

                .icon-btn {
                    width: 40px;
                    height: 40px;
                    border: 0;
                    border-radius: 999px;
                    background: rgba(255,255,255,.22);
                    color: #2A1605;
                    font-size: 24px;
                }

                .avatars {
                    display: flex;
                    align-items: center;
                }

                .avatar {
                    position: relative;
                    overflow: hidden;
                    width: 38px;
                    height: 38px;
                    border-radius: 999px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                    font-weight: 800;
                    color: white;
                    border: 2px solid rgba(255,255,255,.55);
                    box-shadow: 0 4px 16px rgba(0,0,0,.12);
                }

                .avatar img {
                    position: absolute;
                    inset: 0;
                    width: 100%;
                    height: 100%;
                    object-fit: cover;
                    display: none;
                }

                .avatar.has-image img {
                    display: block;
                }

                .avatar + .avatar {
                    margin-left: -10px;
                }

                .avatar.owner {
                    background: linear-gradient(135deg, #FF86A5 0%, #FF5F7C 100%);
                }

                .avatar.peer {
                    background: linear-gradient(135deg, #5E76FF 0%, #3548C8 100%);
                }

                .headline-label {
                    font-size: 15px;
                    font-weight: 600;
                    color: #543716;
                }

                .headline {
                    padding-left: 7.5%;
                }

                .headline-days {
                    font-size: 60px;
                    line-height: .95;
                    font-weight: 900;
                    margin: 2px 0 0;
                    color: #000;
                }

                .hero {
                    position: relative;
                    height: 248px;
                    margin-top: 8px;
                }

                .stage-btn {
                    position: absolute;
                    top: 50%;
                    transform: translateY(-50%);
                    width: 44px;
                    height: 44px;
                    border: 0;
                    border-radius: 999px;
                    background: rgba(255,255,255,.18);
                    color: #6A4A23;
                    font-size: 32px;
                    font-weight: 900;
                    z-index: 2;
                }

                .stage-btn.hidden {
                    visibility: hidden;
                }

                #prev-btn { left: 0; }
                #next-btn { right: 0; }

                .pet-wrap {
                    position: absolute;
                    inset: 0;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }

                .pet {
                    position: relative;
                    width: 220px;
                    height: 220px;
                    transform: scale(1);
                    transition: transform .24s cubic-bezier(.34,1.56,.64,1);
                    animation: bob 2.4s ease-in-out infinite;
                }

                @keyframes bob {
                    0%, 100% { transform: translateY(0px); }
                    50% { transform: translateY(-6px); }
                }

                .pet-shadow {
                    position: absolute;
                    left: 50%;
                    bottom: 26px;
                    width: 98px;
                    height: 26px;
                    transform: translateX(-50%);
                    border-radius: 999px;
                    background: radial-gradient(circle, rgba(0,0,0,.16) 0%, rgba(0,0,0,0) 72%);
                }

                .ear {
                    position: absolute;
                    top: 30px;
                    width: 58px;
                    height: 82px;
                    background: var(--pet-start);
                    clip-path: polygon(50% 0%, 100% 100%, 0% 100%);
                    filter: drop-shadow(0 8px 18px rgba(0,0,0,.08));
                }

                .ear.left { left: 46px; transform: rotate(-14deg); }
                .ear.right { right: 46px; transform: rotate(14deg); }

                .body {
                    position: absolute;
                    left: 50%;
                    top: 56px;
                    width: 124px;
                    height: 118px;
                    transform: translateX(-50%);
                    border-radius: 58px 58px 54px 54px;
                    background: linear-gradient(180deg, var(--pet-start) 0%, var(--pet-end) 100%);
                    box-shadow: 0 18px 32px rgba(0,0,0,.12);
                }

                .eye {
                    position: absolute;
                    top: 40px;
                    width: 18px;
                    height: 18px;
                    border-radius: 999px;
                    background: #fff;
                }

                .eye::after {
                    content: "";
                    position: absolute;
                    left: 6px;
                    top: 7px;
                    width: 6px;
                    height: 6px;
                    border-radius: 999px;
                    background: var(--accent);
                }

                .eye.left { left: 34px; }
                .eye.right { right: 34px; }

                .blush {
                    position: absolute;
                    top: 62px;
                    width: 18px;
                    height: 18px;
                    border-radius: 999px;
                    background: rgba(255,255,255,.34);
                }

                .blush.left { left: 18px; }
                .blush.right { right: 18px; }

                .mouth {
                    position: absolute;
                    left: 50%;
                    top: 72px;
                    width: 26px;
                    height: 10px;
                    transform: translateX(-50%);
                    border-bottom: 3px solid var(--accent);
                    border-radius: 0 0 16px 16px;
                }

                .accessory { position: absolute; inset: 0; }
                .accessory > * { display: none; }

                .pet[data-stage="0"] .flower,
                .pet[data-stage="1"] .collar,
                .pet[data-stage="2"] .sparkles,
                .pet[data-stage="3"] .crown {
                    display: block;
                }

                .flower {
                    position: absolute;
                    right: 36px;
                    top: 34px;
                    width: 42px;
                    height: 42px;
                    border-radius: 50% 50% 44% 44%;
                    background: var(--accent-secondary);
                    transform: rotate(18deg);
                }

                .collar {
                    position: absolute;
                    left: 50%;
                    top: 118px;
                    width: 92px;
                    height: 24px;
                    transform: translateX(-50%);
                    border-radius: 16px;
                    background: var(--accent);
                }

                .collar::after {
                    content: "";
                    position: absolute;
                    left: 50%;
                    top: 19px;
                    width: 12px;
                    height: 12px;
                    transform: translateX(-50%);
                    border-radius: 999px;
                    background: var(--accent-secondary);
                }

                .sparkles {
                    position: absolute;
                    left: 50%;
                    top: 20px;
                    width: 120px;
                    height: 36px;
                    transform: translateX(-50%);
                }

                .sparkles::before,
                .sparkles::after,
                .sparkles span {
                    content: "";
                    position: absolute;
                    width: 12px;
                    height: 12px;
                    border-radius: 999px;
                    background: var(--accent-secondary);
                }

                .sparkles::before { left: 8px; top: 10px; }
                .sparkles::after { right: 8px; top: 10px; }
                .sparkles span { left: 50%; top: 0; transform: translateX(-50%); background: var(--accent); }

                .crown {
                    position: absolute;
                    left: 50%;
                    top: 18px;
                    width: 74px;
                    height: 36px;
                    transform: translateX(-50%);
                    background: var(--accent);
                    clip-path: polygon(0% 100%, 0% 48%, 18% 48%, 30% 0%, 50% 42%, 70% 0%, 82% 48%, 100% 48%, 100% 100%);
                    border-radius: 0 0 12px 12px;
                }

                .pet.locked {
                    filter: grayscale(1) brightness(.82);
                    opacity: .78;
                }

                .lock-badge {
                    position: absolute;
                    left: 50%;
                    bottom: 24px;
                    transform: translateX(-50%);
                    padding: 8px 12px;
                    border-radius: 999px;
                    background: rgba(17,24,39,.48);
                    backdrop-filter: blur(10px);
                    color: #fff;
                    font-size: 12px;
                    font-weight: 800;
                    display: none;
                }

                .lock-badge.visible {
                    display: block;
                }

                .name-row {
                    width: fit-content;
                    margin: 4px auto 0;
                    padding: 8px 14px;
                    border-radius: 999px;
                    background: rgba(255,255,255,.22);
                    color: #000;
                    font-size: 18px;
                    font-weight: 800;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .progress-track {
                    margin: 16px 16px 0;
                    height: 22px;
                    border-radius: 999px;
                    background: rgba(255,255,255,.9);
                    overflow: hidden;
                    position: relative;
                    box-shadow: inset 0 0 0 1px rgba(255,255,255,.4);
                }

                .progress-fill {
                    position: absolute;
                    inset: 0 auto 0 0;
                    width: 0%;
                    border-radius: 999px;
                    background: linear-gradient(90deg, var(--accent-secondary), color-mix(in srgb, var(--accent) 82%, white 18%));
                    transition: width .35s ease;
                }

                .progress-text {
                    position: absolute;
                    inset: 0;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 11px;
                    font-weight: 800;
                    color: var(--accent);
                }

                .progress-subtext {
                    margin-top: 12px;
                    text-align: center;
                    font-size: 12px;
                    font-weight: 600;
                    color: rgba(55,65,81,.78);
                }

                .card {
                    margin-top: 18px;
                    padding: 20px;
                    border-radius: 28px;
                    background: #fff;
                    box-shadow: 0 14px 36px rgba(15,23,42,.08);
                }

                .card h3 {
                    margin: 0 0 16px;
                    font-size: 16px;
                    font-weight: 800;
                }

                .task {
                    display: flex;
                    align-items: flex-start;
                    gap: 12px;
                }

                .task + .task {
                    margin-top: 14px;
                    padding-top: 14px;
                    border-top: 1px solid #F3F4F6;
                }

                .task-icon {
                    flex: 0 0 32px;
                    width: 32px;
                    height: 32px;
                    border-radius: 999px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 16px;
                    font-weight: 900;
                    background: #F3F4F6;
                    color: #9CA3AF;
                    box-shadow: inset 0 0 0 1px #E5E7EB;
                }

                .task.done .task-icon {
                    background: var(--accent);
                    color: #fff;
                    box-shadow: none;
                }

                .task-body {
                    flex: 1 1 auto;
                    min-width: 0;
                    display: flex;
                    flex-direction: column;
                    gap: 10px;
                }

                .task-head {
                    display: flex;
                    align-items: flex-start;
                    justify-content: space-between;
                    gap: 12px;
                }

                .task-title {
                    flex: 1 1 auto;
                    font-size: 15px;
                    font-weight: 800;
                    color: #111827;
                }

                .task-reward {
                    flex: 0 0 auto;
                    font-weight: 800;
                    color: #9CA3AF;
                    font-size: 12px;
                }

                .task.done .task-reward,
                .task.done .task-metric-label,
                .task.done .task-metric-value {
                    color: var(--accent);
                }

                .task-metrics {
                    display: grid;
                    gap: 8px;
                }

                .task-metric {
                    display: grid;
                    gap: 4px;
                }

                .task-metric-row {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 12px;
                }

                .task-metric-label {
                    font-size: 12px;
                    font-weight: 700;
                    color: #6B7280;
                }

                .task-metric-value {
                    font-size: 12px;
                    font-weight: 800;
                    white-space: nowrap;
                    color: #374151;
                }

                .task-metric-track {
                    height: 6px;
                    border-radius: 999px;
                    overflow: hidden;
                    background: #F3F4F6;
                }

                .task-metric-fill {
                    height: 100%;
                    border-radius: inherit;
                    background: linear-gradient(90deg, var(--accent-secondary), var(--accent));
                }

                .badges {
                    display: flex;
                    width: 100%;
                    justify-content: space-around;
                    gap: 0;
                    overflow-x: hidden;
                    padding-bottom: 2px;
                }

                .badges::-webkit-scrollbar {
                    display: none;
                }

                .badge {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 8px;
                    min-width: 44px;
                    flex: 1 1 0;
                }

                .badge-circle {
                    width: 44px;
                    height: 44px;
                    border-radius: 999px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-weight: 800;
                    font-size: 13px;
                    color: #94A3B8;
                    background: #fff;
                    box-shadow: inset 0 0 0 1px #E5E7EB;
                }

                .badge.earned .badge-circle {
                    background: var(--accent);
                    color: #fff;
                    box-shadow: none;
                }

                .badge-label {
                    font-size: 12px;
                    font-weight: 600;
                    color: #9CA3AF;
                }

                .badge.earned .badge-label {
                    color: #374151;
                }

                .modal {
                    position: fixed;
                    inset: 0;
                    background: rgba(15,23,42,.42);
                    display: none;
                    align-items: center;
                    justify-content: center;
                    padding: 20px;
                    backdrop-filter: blur(12px);
                }

                .modal.visible {
                    display: flex;
                }

                .modal-card {
                    width: min(360px, 100%);
                    border-radius: 24px;
                    background: #fff;
                    padding: 20px;
                    box-shadow: 0 18px 42px rgba(15,23,42,.18);
                }

                .modal-title {
                    font-size: 18px;
                    font-weight: 800;
                    margin: 0 0 14px;
                }

                .modal-input {
                    width: 100%;
                    border: 0;
                    outline: none;
                    border-radius: 18px;
                    background: #F3F4F6;
                    padding: 14px 16px;
                    font-size: 16px;
                    color: #111827;
                }

                .modal-actions {
                    margin-top: 14px;
                    display: flex;
                    gap: 10px;
                    justify-content: flex-end;
                }

                .modal-btn {
                    border: 0;
                    border-radius: 16px;
                    padding: 12px 16px;
                    font-size: 14px;
                    font-weight: 800;
                }

                .modal-btn.secondary {
                    background: #F3F4F6;
                    color: #374151;
                }

                .modal-btn.primary {
                    background: #111827;
                    color: white;
                }
            </style>
        </head>
        <body>
            <div class="shell">
                <div class="app">
                    <div class="topbar">
                        <button class="icon-btn" onclick="closeApp()">×</button>
                        <div class="avatars">
                            <div class="avatar owner" id="owner-avatar">
                                <img id="owner-avatar-img" alt="">
                                <span id="owner-avatar-text"></span>
                            </div>
                            <div class="avatar peer" id="peer-avatar">
                                <img id="peer-avatar-img" alt="">
                                <span id="peer-avatar-text"></span>
                            </div>
                        </div>
                    </div>

                    <div class="headline">
                        <div class="headline-label" id="streak-label"></div>
                        <div class="headline-days" id="streak-days">0</div>
                    </div>

                    <div class="hero">
                        <button id="prev-btn" class="stage-btn" onclick="prevStage()">‹</button>
                        <button id="next-btn" class="stage-btn" onclick="nextStage()">›</button>
                        <div class="pet-wrap">
                            <div class="pet" id="pet">
                                <div class="pet-shadow"></div>
                                <div class="ear left"></div>
                                <div class="ear right"></div>
                                <div class="body">
                                    <div class="eye left"></div>
                                    <div class="eye right"></div>
                                    <div class="blush left"></div>
                                    <div class="blush right"></div>
                                    <div class="mouth"></div>
                                </div>
                                <div class="accessory">
                                    <div class="flower"></div>
                                    <div class="collar"></div>
                                    <div class="sparkles"><span></span></div>
                                    <div class="crown"></div>
                                </div>
                            </div>
                            <div class="lock-badge" id="lock-badge"></div>
                        </div>
                    </div>

                    <div class="name-row" onclick="openRename()">
                        <span id="pet-name"></span>
                        <span>✎</span>
                    </div>

                    <div class="progress-track">
                        <div class="progress-fill" id="progress-fill"></div>
                        <div class="progress-text" id="progress-text"></div>
                    </div>
                    <div class="progress-subtext" id="progress-subtext"></div>

                    <div class="card">
                        <h3 id="tasks-title"></h3>
                        <div id="tasks"></div>
                    </div>

                    <div class="card">
                        <h3 id="badges-title"></h3>
                        <div class="badges" id="badges"></div>
                    </div>
                </div>
            </div>

            <div class="modal" id="rename-modal" onclick="closeRename(event)">
                <div class="modal-card" onclick="event.stopPropagation()">
                    <h3 class="modal-title" id="rename-title"></h3>
                    <input id="rename-input" class="modal-input" maxlength="20">
                    <div class="modal-actions">
                        <button class="modal-btn secondary" id="rename-cancel" onclick="closeRename()"></button>
                        <button class="modal-btn primary" id="rename-save" onclick="submitRename()"></button>
                    </div>
                </div>
            </div>

            <script>
                var state = null;
                var selectedStageIndex = 0;

                function bridgeCall(name, value) {
                    try {
                        if (!window.AndroidBridge || typeof window.AndroidBridge[name] !== 'function') {
                            return;
                        }

                        if (typeof value === 'undefined') {
                            window.AndroidBridge[name]();
                        } else {
                            window.AndroidBridge[name](String(value));
                        }
                    } catch (_) {}
                }

                function logToAndroid(message) {
                    bridgeCall('log', message);
                }

                function errorToAndroid(message) {
                    bridgeCall('error', message);
                }

                function describeError(error) {
                    if (!error) {
                        return 'unknown error';
                    }

                    if (error.stack) {
                        return String(error.stack);
                    }

                    if (error.message) {
                        return String(error.message);
                    }

                    return String(error);
                }

                function safeCall(name, callback) {
                    try {
                        return callback();
                    } catch (error) {
                        errorToAndroid(name + ': ' + describeError(error));
                        return null;
                    }
                }

                window.onerror = function(message, source, lineno, colno, error) {
                    var location = source ? source + ':' + lineno + ':' + (colno || 0) : 'inline';
                    var payload = String(message) + ' @ ' + location;
                    if (error) {
                        payload += '\n' + describeError(error);
                    }
                    errorToAndroid(payload);
                    return false;
                };

                function closeApp() {
                    bridgeCall('close');
                }

                function openRename() {
                    return safeCall('openRename', function() {
                        if (!state) {
                            return;
                        }

                        document.getElementById('rename-title').textContent = state.texts.renameTitle;
                        document.getElementById('rename-input').placeholder = state.texts.renameHint;
                        document.getElementById('rename-input').value = state.petName || '';
                        document.getElementById('rename-cancel').textContent = state.texts.renameCancel;
                        document.getElementById('rename-save').textContent = state.texts.renameSave;
                        document.getElementById('rename-modal').classList.add('visible');
                        document.getElementById('rename-input').focus();
                        document.getElementById('rename-input').select();
                    });
                }

                function closeRename(event) {
                    return safeCall('closeRename', function() {
                        if (event) {
                            event.stopPropagation();
                        }
                        document.getElementById('rename-modal').classList.remove('visible');
                    });
                }

                function submitRename() {
                    return safeCall('submitRename', function() {
                        var input = document.getElementById('rename-input');
                        var value = (input.value || '').trim();
                        if (!value) {
                            return;
                        }

                        bridgeCall('rename', value);
                        closeRename();
                    });
                }

                function resolveUnlockedIndex(points) {
                    var unlocked = 0;
                    var index;

                    for (index = 0; index < state.stages.length; index += 1) {
                        if (points >= state.stages[index].maxPoints && index < state.stages.length - 1) {
                            unlocked = index + 1;
                        }
                    }

                    return unlocked;
                }

                function prevStage() {
                    return safeCall('prevStage', function() {
                        if (selectedStageIndex <= 0) {
                            return;
                        }

                        selectedStageIndex -= 1;
                        render(false);
                    });
                }

                function nextStage() {
                    return safeCall('nextStage', function() {
                        var unlockedIndex;

                        if (!state) {
                            return;
                        }

                        unlockedIndex = resolveUnlockedIndex(state.points);
                        if (selectedStageIndex >= state.stages.length - 1 || selectedStageIndex > unlockedIndex) {
                            return;
                        }

                        selectedStageIndex += 1;
                        render(true);
                    });
                }

                function doApplyState(nextState) {
                    var hadState = state !== null;
                    var unlockedIndex;
                    var preferredStageIndex;

                    state = nextState;
                    unlockedIndex = resolveUnlockedIndex(state.points);

                    if (!hadState) {
                        preferredStageIndex =
                            state.preferredStageIndex !== null && typeof state.preferredStageIndex !== 'undefined'
                                ? state.preferredStageIndex
                                : unlockedIndex;

                        selectedStageIndex = Math.min(
                            preferredStageIndex,
                            state.stages.length - 1
                        );
                    } else {
                        selectedStageIndex = Math.min(
                            selectedStageIndex,
                            Math.min(unlockedIndex + 1, state.stages.length - 1)
                        );
                    }

                    logToAndroid(
                        'applyState: points=' + state.points +
                        ', streakDays=' + state.streakDays +
                        ', tasks=' + (state.tasks ? state.tasks.length : 0)
                    );
                    render(false);
                }

                window.applyState = function(nextState) {
                    return safeCall('applyState', function() {
                        doApplyState(nextState);
                    });
                };

                function render(animatePet) {
                    if (typeof animatePet === 'undefined') {
                        animatePet = false;
                    }

                    return safeCall('render', function() {
                        var unlockedIndex;
                        var stage;
                        var locked;
                        var root;
                        var pet;
                        var lockBadge;
                        var prevBtn;
                        var nextBtn;

                        if (!state) {
                            return;
                        }

                        unlockedIndex = resolveUnlockedIndex(state.points);
                        stage = state.stages[selectedStageIndex];
                        locked = selectedStageIndex > unlockedIndex;
                        root = document.documentElement;

                        root.style.setProperty('--bg-start', locked ? '#D1D5DB' : stage.gradientStart);
                        root.style.setProperty('--bg-end', locked ? '#F3F4F6' : stage.gradientEnd);
                        root.style.setProperty('--pet-start', stage.petStart);
                        root.style.setProperty('--pet-end', stage.petEnd);
                        root.style.setProperty('--accent', locked ? '#667085' : stage.accent);
                        root.style.setProperty('--accent-secondary', locked ? '#CBD5E1' : stage.accentSecondary);

                        bindAvatar('owner', state.ownerInitial, state.ownerAvatarSrc);
                        bindAvatar('peer', state.peerInitial, state.peerAvatarSrc);
                        document.getElementById('streak-label').textContent = state.texts.streakDays;
                        document.getElementById('streak-days').textContent = String(state.streakDays);
                        document.getElementById('pet-name').textContent = state.petName;
                        document.getElementById('tasks-title').textContent = state.texts.tasksTitle;
                        document.getElementById('badges-title').textContent = state.texts.badgesTitle;

                        pet = document.getElementById('pet');
                        pet.setAttribute('data-stage', String(selectedStageIndex));
                        pet.classList.toggle('locked', locked);
                        if (animatePet) {
                            pet.style.transform = 'scale(0.92)';
                            requestAnimationFrame(function() {
                                pet.style.transform = 'scale(1)';
                            });
                        } else {
                            pet.style.transform = 'scale(1)';
                        }

                        lockBadge = document.getElementById('lock-badge');
                        lockBadge.textContent = state.texts.locked;
                        lockBadge.classList.toggle('visible', locked);

                        prevBtn = document.getElementById('prev-btn');
                        nextBtn = document.getElementById('next-btn');
                        prevBtn.classList.toggle('hidden', selectedStageIndex <= 0);
                        nextBtn.classList.toggle(
                            'hidden',
                            !(selectedStageIndex < state.stages.length - 1 && selectedStageIndex <= unlockedIndex)
                        );

                        renderProgress(stage, locked);
                        renderTasks();
                        renderBadges();
                    });
                }

                function bindAvatar(prefix, initial, src) {
                    var root = document.getElementById(prefix + '-avatar');
                    var text = document.getElementById(prefix + '-avatar-text');
                    var img = document.getElementById(prefix + '-avatar-img');

                    text.textContent = initial || '';

                    if (src) {
                        img.src = src;
                        root.classList.add('has-image');
                    } else {
                        img.removeAttribute('src');
                        root.classList.remove('has-image');
                    }
                }

                function renderProgress(stage, locked) {
                    var fill = document.getElementById('progress-fill');
                    var text = document.getElementById('progress-text');
                    var subtext = document.getElementById('progress-subtext');
                    var prevMax;
                    var range;
                    var pointsInStage;
                    var progress;

                    if (locked) {
                        fill.style.width = '0%';
                        text.textContent = state.texts.locked;
                        subtext.textContent = state.texts.lockedSubtext;
                        return;
                    }

                    prevMax = selectedStageIndex > 0 ? state.stages[selectedStageIndex - 1].maxPoints : 0;
                    range = stage.maxPoints - prevMax;
                    pointsInStage = Math.max(0, Math.min(range, state.points - prevMax));
                    progress = range === 0 ? 100 : (pointsInStage / range) * 100;
                    fill.style.width = progress + '%';

                    if (selectedStageIndex === state.stages.length - 1 && state.points >= stage.maxPoints) {
                        text.textContent = state.texts.maxLevel;
                        subtext.textContent = state.texts.maxLevel;
                    } else {
                        text.textContent = pointsInStage + '/' + range;
                        subtext.textContent =
                            state.texts.pointsToEvolution.replace('{count}', String(range - pointsInStage));
                    }
                }

                function renderTasks() {
                    var tasks = document.getElementById('tasks');
                    var index;
                    var task;
                    var html = '';
                    var icon;

                    for (index = 0; index < state.tasks.length; index += 1) {
                        task = state.tasks[index];
                        icon = task.completed ? '✓' : '○';

                        html += ''
                            + '<div class="task ' + (task.completed ? 'done' : '') + '">'
                            +   '<div class="task-icon">' + icon + '</div>'
                            +   '<div class="task-body">'
                            +     '<div class="task-head">'
                            +       '<div class="task-title">' + escapeHtml(task.title) + '</div>'
                            +       '<div class="task-reward">+' + task.reward + '</div>'
                            +     '</div>'
                            +     '<div class="task-metrics">'
                            +       renderTaskMetric(state.texts.progressYou, task.ownerProgress, task.target)
                            +       renderTaskMetric(state.texts.progressPeer, task.peerProgress, task.target)
                            +     '</div>'
                            +   '</div>'
                            + '</div>';
                    }

                    tasks.innerHTML = html;
                }

                function renderTaskMetric(label, value, target) {
                    var safeTarget = target > 0 ? target : 1;
                    var safeValue = Math.max(0, Math.min(value, safeTarget));
                    var percent = Math.max(0, Math.min(100, (safeValue / safeTarget) * 100));

                    return ''
                        + '<div class="task-metric">'
                        +   '<div class="task-metric-row">'
                        +     '<div class="task-metric-label">' + escapeHtml(label) + '</div>'
                        +     '<div class="task-metric-value">' + safeValue + '/' + safeTarget + '</div>'
                        +   '</div>'
                        +   '<div class="task-metric-track">'
                        +     '<div class="task-metric-fill" style="width: ' + percent + '%"></div>'
                        +   '</div>'
                        + '</div>';
                }

                function renderBadges() {
                    var badges = document.getElementById('badges');
                    var index;
                    var day;
                    var html = '';

                    for (index = 0; index < state.badges.length; index += 1) {
                        day = state.badges[index];
                        html += ''
                            + '<div class="badge ' + (state.streakDays >= day ? 'earned' : '') + '">'
                            + '<div class="badge-circle">' + day + '</div>'
                            + '<div class="badge-label">' + day + 'd</div>'
                            + '</div>';
                    }

                    badges.innerHTML = html;
                }

                function escapeHtml(value) {
                    return String(value)
                        .replace(/&/g, '&amp;')
                        .replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;')
                        .replace(/"/g, '&quot;')
                        .replace(/'/g, '&#39;');
                }

                logToAndroid('script loaded');
            </script>
        </body>
        </html>
    """.trimIndent()

    private inner class Bridge {
        @JavascriptInterface
        fun close() {
            AndroidUtilities.runOnUIThread {
                if (isShowing) {
                    dismiss()
                }
            }
        }

        @JavascriptInterface
        fun rename(value: String?) {
            val normalized = value?.trim()?.take(20).orEmpty()
            if (normalized.isEmpty()) {
                return
            }

            AndroidUtilities.runOnUIThread {
                onRenameRequested(normalized)
            }
        }

        @JavascriptInterface
        fun log(value: String?) {
            log("js: ${value?.trim().orEmpty()}")
        }

        @JavascriptInterface
        fun error(value: String?) {
            log("js-error: ${value?.trim().orEmpty()}")
        }
    }
}
