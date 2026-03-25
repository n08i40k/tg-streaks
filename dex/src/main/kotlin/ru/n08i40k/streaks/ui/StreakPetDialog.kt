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
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.Translator
import java.io.ByteArrayOutputStream
import java.io.File

class StreakPetDialog(
    private val fragment: BaseFragment,
    initialState: StreakPetsController.UiState,
    private val resourcesProvider: ResourcesProvider,
    private val translator: Translator,
    private val onRenameRequested: (String) -> Unit,
    private val onDismissed: (() -> Unit)? = null,
) : Dialog(
    fragment.context ?: fragment.parentActivity
    ?: throw IllegalStateException("Cannot create streak pet dialog without a context"),
    android.R.style.Theme_Translucent_NoTitleBar_Fullscreen
) {
    private data class TaskUi(
        val title: String,
        val reward: Int,
        val target: Int,
        val ownerProgress: Int,
        val peerProgress: Int,
        val isCompleted: Boolean,
    )

    private val stages = Plugin.getInstance().streakPetLevelRegistry.levels().ifEmpty {
        throw IllegalStateException("Streak pet levels are not registered")
    }

    private var state = initialState
    private var pageReady = false
    private var destroyed = false
    private val avatarCache = mutableMapOf<Long, String?>()

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

        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface("AndroidBridge")
        webView.onPause()
        webView.pauseTimers()
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
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
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
                StreakPetUiResources.loadSheetBaseUrl(resourcesProvider),
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
                            put("mediaSrc", stage.imageResourcePath)
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

        avatarCache[user.id]?.let { return it }

        val result = try {
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

        avatarCache[user.id] = result
        return result
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

    private fun buildHtml(safeTopPx: Int): String =
        StreakPetUiResources.loadSheetHtml(resourcesProvider, safeTopPx)

    @Suppress("unused")
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
            this@StreakPetDialog.log("js: ${value?.trim().orEmpty()}")
        }

        @JavascriptInterface
        fun error(value: String?) {
            this@StreakPetDialog.log("js-error: ${value?.trim().orEmpty()}")
        }
    }
}
