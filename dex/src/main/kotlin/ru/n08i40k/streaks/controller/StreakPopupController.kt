@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.controller

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.resource.ResourcesProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StreakPopupController(
    db: PluginDatabase,
    private val resourcesProvider: ResourcesProvider,
) {
    companion object {
        private const val POPUP_KIND_CREATED = "created"
        private const val POPUP_KIND_UPGRADED = "upgraded"
        private const val MAX_QUEUE_SIZE_PER_CHAT = 5
        private const val POPUP_AUTO_DISMISS_MS = 6_000L
        private const val POPUP_MEDIA_SIZE_DP = 440f
    }

    private val dao = db.scheduledStreakPopupDao()
    private val isShowing = AtomicBoolean(false)

    suspend fun enqueueForTransition(
        accountId: Int,
        peerUserId: Long,
        before: Streak?,
        after: Streak?
    ) {
        if (accountId < 0 || peerUserId <= 0L || after == null || after.dead || after.length < 3) {
            return
        }

        when {
            before == null || before.length < 3 -> {
                enqueue(
                    accountId = accountId,
                    peerUserId = peerUserId,
                    kind = POPUP_KIND_CREATED,
                    days = after.length,
                    level = after.level,
                    dedupeKey = "created:$accountId:$peerUserId:${after.level.length}"
                )
            }

            !before.dead && after.level.length > before.level.length -> {
                enqueue(
                    accountId = accountId,
                    peerUserId = peerUserId,
                    kind = POPUP_KIND_UPGRADED,
                    days = after.length,
                    level = after.level,
                    dedupeKey = "upgraded:$accountId:$peerUserId:${after.level.length}"
                )
            }
        }
    }

    suspend fun enqueueCreated(accountId: Int, peerUserId: Long, days: Int, level: StreakLevel) {
        if (days < 3) {
            return
        }

        enqueue(
            accountId = accountId,
            peerUserId = peerUserId,
            kind = POPUP_KIND_CREATED,
            days = days,
            level = level,
            dedupeKey = "created:$accountId:$peerUserId:${level.length}"
        )
    }

    suspend fun enqueueUpgrade(accountId: Int, peerUserId: Long, days: Int, level: StreakLevel) {
        enqueue(
            accountId = accountId,
            peerUserId = peerUserId,
            kind = POPUP_KIND_UPGRADED,
            days = days,
            level = level,
            dedupeKey = "upgraded:$accountId:$peerUserId:${level.length}"
        )
    }

    suspend fun flushCurrentChat() {
        val relation = resolveOpenChatRelation() ?: return
        flushRelation(relation.accountId, relation.peerUserId)
    }

    private suspend fun enqueue(
        accountId: Int,
        peerUserId: Long,
        kind: String,
        days: Int,
        level: StreakLevel,
        dedupeKey: String,
    ) {
        if (peerUserId <= 0L) {
            return
        }

        val insertedId = dao.insert(
            ScheduledStreakPopup(
                accountId = accountId,
                peerUserId = peerUserId,
                kind = kind,
                peerName = resolvePeerName(accountId, peerUserId),
                days = days,
                accentColor = level.colorInt,
                emojiDocumentId = level.documentId,
                popupResourceName = level.popupResourceName,
                dedupeKey = dedupeKey,
                scheduledAt = System.currentTimeMillis(),
            )
        )

        if (insertedId == -1L) {
            return
        }

        dao.trimRelationQueue(accountId, peerUserId, MAX_QUEUE_SIZE_PER_CHAT)

        val relation = resolveOpenChatRelation()
        if (relation?.accountId == accountId && relation.peerUserId == peerUserId) {
            flushRelation(accountId, peerUserId)
        }
    }

    private suspend fun flushRelation(accountId: Int, peerUserId: Long) {
        if (!isShowing.compareAndSet(false, true)) {
            return
        }

        val popup = dao.findFirstByRelation(accountId, peerUserId)
        if (popup == null) {
            isShowing.set(false)
            return
        }

        val shown = showPopup(popup) {
            Plugin.getInstance().enqueueTask("delete pending popup from database") {
                dao.delete(popup)
                isShowing.set(false)
                flushCurrentChat()
            }
        }

        if (!shown) {
            isShowing.set(false)
        }
    }

    private fun showPopup(
        popup: ScheduledStreakPopup,
        onDismiss: () -> Unit,
    ): Boolean {
        val relation = resolveOpenChatRelation() ?: return false
        if (relation.accountId != popup.accountId || relation.peerUserId != popup.peerUserId) {
            return false
        }

        val context = resolvePopupContext() ?: return false

        AndroidUtilities.runOnUIThread {
            try {
                val dialog = Dialog(context)
                dialog.requestWindowFeature(1)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)

                val rootContainer = FrameLayout(context).apply {
                    setBackgroundColor(Color.argb(200, 20, 20, 24))
                    setOnClickListener { dialog.dismiss() }
                }

                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setPadding(
                        AndroidUtilities.dp(16f),
                        AndroidUtilities.dp(14f),
                        AndroidUtilities.dp(16f),
                        AndroidUtilities.dp(14f),
                    )
                }

                var mediaWebView: WebView? = null
                var imageView: ImageView? = null
                var animatedDrawable: AnimatedImageDrawable? = null
                val resourceFile = resourcesProvider.resolvePopupResource(popup.popupResourceName)
                if (resourceFile != null) {
                    if (resourceFile.extension.equals("webp", ignoreCase = true)) {
                        imageView = ImageView(context).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                try {
                                    val drawable = ImageDecoder.decodeDrawable(
                                        ImageDecoder.createSource(resourceFile)
                                    )
                                    setImageDrawable(drawable)
                                    animatedDrawable = drawable as? AnimatedImageDrawable
                                    animatedDrawable?.repeatCount = 0
                                    animatedDrawable?.start()
                                } catch (_: Throwable) {
                                    BitmapFactory.decodeFile(resourceFile.absolutePath)
                                        ?.let(::setImageBitmap)
                                }
                            } else {
                                BitmapFactory.decodeFile(resourceFile.absolutePath)
                                    ?.let(::setImageBitmap)
                            }
                        }

                        container.addView(
                            imageView,
                            LinearLayout.LayoutParams(
                                AndroidUtilities.dp(POPUP_MEDIA_SIZE_DP),
                                AndroidUtilities.dp(POPUP_MEDIA_SIZE_DP),
                            ).apply {
                                bottomMargin = AndroidUtilities.dp(10f)
                            }
                        )
                    } else {
                        mediaWebView = createPopupVideoWebView(context, resourceFile)

                        container.addView(
                            mediaWebView,
                            LinearLayout.LayoutParams(
                                AndroidUtilities.dp(POPUP_MEDIA_SIZE_DP),
                                AndroidUtilities.dp(POPUP_MEDIA_SIZE_DP),
                            ).apply {
                                bottomMargin = AndroidUtilities.dp(10f)
                            }
                        )
                    }
                }

                rootContainer.addView(
                    container,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                )

                dialog.setContentView(
                    rootContainer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                )

                dialog.window?.let { window ->
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    window.setGravity(Gravity.CENTER)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }

                val releaseEmoji = {
                    try {
                        animatedDrawable?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        mediaWebView?.onPause()
                        mediaWebView?.stopLoading()
                        mediaWebView?.loadUrl("about:blank")
                        mediaWebView?.destroy()
                    } catch (_: Throwable) {
                    }
                    try {
                        imageView?.setImageDrawable(null)
                    } catch (_: Throwable) {
                    }
                }

                dialog.setOnDismissListener {
                    releaseEmoji()
                    onDismiss()
                }

                dialog.show()

                AndroidUtilities.runOnUIThread(
                    {
                        try {
                            if (dialog.isShowing) {
                                dialog.dismiss()
                            }
                        } catch (_: Throwable) {
                        }
                    },
                    POPUP_AUTO_DISMISS_MS
                )
            } catch (e: Throwable) {
                Plugin.getInstance().logger.fatal("Failed to show streak popup", e)
                onDismiss()
            }
        }

        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupVideoWebView(
        context: android.content.Context,
        resourceFile: File,
    ): WebView {
        val baseUrl = resourceFile.parentFile?.toURI()?.toString() ?: "file:///"
        val html = buildPopupVideoHtml(resourceFile.name)

        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
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
                    view?.evaluateJavascript("window.startPlayback && window.startPlayback();", null)
                }
            }

            loadDataWithBaseURL(
                baseUrl,
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun buildPopupVideoHtml(fileName: String): String {
        val escapedFileName = JSONObject.quote(fileName)

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
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
                    }

                    #video {
                        width: 100%;
                        height: 100%;
                        object-fit: contain;
                        background: transparent;
                        opacity: 0;
                        transition: opacity .16s ease;
                        pointer-events: none;
                    }
                </style>
            </head>
            <body>
                <video id="video" muted autoplay playsinline preload="auto"></video>
                <script>
                    var video = document.getElementById('video');
                    var src = $escapedFileName;

                    function freezeLastFrame() {
                        var duration = Number(video.duration || 0);
                        video.pause();
                        if (isFinite(duration) && duration > 0) {
                            try {
                                video.currentTime = Math.max(duration - 0.001, 0);
                            } catch (_) {}
                        }
                    }

                    window.startPlayback = function() {
                        if (!video.src) {
                            video.src = src;
                        }

                        video.onloadeddata = function() {
                            video.style.opacity = '1';
                        };

                        video.onended = function() {
                            freezeLastFrame();
                        };

                        video.onerror = function() {
                            video.style.opacity = '0';
                        };

                        var playPromise = video.play();
                        if (playPromise && typeof playPromise.catch === 'function') {
                            playPromise.catch(function() {});
                        }
                    };

                    window.startPlayback();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun resolvePopupContext(): android.content.Context? {
        val fragment = LaunchActivity.getSafeLastFragment()

        return try {
            fragment?.parentActivity
                ?: fragment?.context
                ?: org.telegram.messenger.ApplicationLoader.applicationContext
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveOpenChatRelation(): OpenChatRelation? {
        val fragment = LaunchActivity.getSafeLastFragment() as? ChatActivity ?: return null
        val dialogId = fragment.dialogId

        if (dialogId <= 0L) {
            return null
        }

        return OpenChatRelation(
            accountId = UserConfig.selectedAccount,
            peerUserId = dialogId
        )
    }

    private fun resolvePeerName(accountId: Int, peerUserId: Long): String {
        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId)
        val fullName = peerUser?.let { UserObject.getUserName(it) }?.takeIf { it.isNotBlank() }

        return fullName ?: peerUserId.toString()
    }

    private data class OpenChatRelation(
        val accountId: Int,
        val peerUserId: Long,
    )
}
