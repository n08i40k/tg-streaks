package ru.n08i40k.streaks.controller

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedFileDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.database.dao.ScheduledStreakPopupDao
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.dp
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class StreakPopupController(
    private val dao: ScheduledStreakPopupDao,
    private val resourcesProvider: ResourcesProvider,
) {
    companion object {
        private const val POPUP_KIND_CREATED = "created"
        private const val POPUP_KIND_UPGRADED = "upgraded"
        private const val MAX_QUEUE_SIZE_PER_CHAT = 5
        private const val POPUP_AUTO_DISMISS_MS = 6_000L
        private const val POPUP_MEDIA_SIZE_DP = 440
    }

    private val isShowing = AtomicBoolean(false)

    suspend fun enqueueForTransition(
        accountId: Int,
        peerUserId: Long,
        before: Streak?,
        after: Streak?
    ) {
        if (accountId < 0
            || peerUserId <= 0L
            || after == null
            || after.ended
            || after.length < 3
        )
            return

        val key = when {
            before == null || before.length < 3 ->
                "$POPUP_KIND_CREATED:$accountId:$peerUserId:${after.level.length}"

            !before.ended && after.level > before.level ->
                "$POPUP_KIND_UPGRADED:$accountId:$peerUserId:${after.level.length}"

            else -> return
        }

        val insertedId = dao.insert(
            ScheduledStreakPopup(
                accountId = accountId,
                peerUserId = peerUserId,
                popupResourceName = after.level.popupResourceName,
                dedupeKey = key,
                scheduledAt = System.currentTimeMillis(),
            )
        )

        if (insertedId == -1L)
            return

        dao.trimRelationQueue(accountId, peerUserId, MAX_QUEUE_SIZE_PER_CHAT)

        val relation = resolveOpenChatRelation()

        if (relation?.accountId == accountId && relation.peerUserId == peerUserId)
            flushRelation(accountId, peerUserId)
    }


    suspend fun flushCurrentChat() {
        val relation = resolveOpenChatRelation() ?: return
        flushRelation(relation.accountId, relation.peerUserId)
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
            Plugin.getInstance()
                .enqueueTask("delete pending popup from database") {
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
        val relation = resolveOpenChatRelation()
            ?: return false

        if (relation.accountId != popup.accountId
            || relation.peerUserId != popup.peerUserId
        )
            return false

        val context = resolvePopupContext()
            ?: return false

        runOnMainThread {
            var videoView: BackupImageView? = null
            var videoDrawable: AnimatedFileDrawable? = null
            var imageView: ImageView? = null
            var animatedDrawable: AnimatedImageDrawable? = null

            fun releaseMedia() {
                animatedDrawable?.stop()
                imageView?.setImageDrawable(null)

                videoDrawable?.apply {
                    stop()
                    videoView?.imageReceiver?.let(::removeParent)
                    setParentView(null)
                    recycle()
                }
                videoView?.setImageDrawable(null)
                videoDrawable = null
            }

            val result = Logger.tryOrFatal("show streak popup") {
                val dialog = Dialog(context).apply {
                    requestWindowFeature(1)
                    setCancelable(true)
                    setCanceledOnTouchOutside(true)
                }

                val rootContainer = FrameLayout(context).apply {
                    setBackgroundColor(Color.argb(200, 20, 20, 24))
                    setOnClickListener { dialog.dismiss() }
                }

                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setPadding(
                        dp(16),
                        dp(14),
                        dp(16),
                        dp(14),
                    )
                }

                dialog.setOnDismissListener {
                    try {
                        releaseMedia()
                    } finally {
                        onDismiss()
                    }
                }

                val resourceFile =
                    resourcesProvider.resolvePopupResource(popup.popupResourceName)
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
                                dp(POPUP_MEDIA_SIZE_DP),
                                dp(POPUP_MEDIA_SIZE_DP),
                            ).apply {
                                bottomMargin = dp(10)
                            }
                        )
                    } else {
                        videoView = BackupImageView(context).apply {
                            setAspectFit(true)
                            imageReceiver.currentAccount = popup.accountId
                            imageReceiver.animatedFileDrawableRepeatMaxCount = 1
                        }

                        // Open and decode on Telegram's worker queue; retain the last frame.
                        videoDrawable = AnimatedFileDrawable(
                            resourceFile, false, 0L, 0, null, null, null, 0L,
                            popup.accountId, false, null
                        ).apply {
                            setIsWebmSticker(true)
                            setUseSharedQueue(true)
                        }

                        videoView.setImageDrawable(videoDrawable)

                        container.addView(
                            videoView,
                            LinearLayout.LayoutParams(
                                dp(POPUP_MEDIA_SIZE_DP),
                                dp(POPUP_MEDIA_SIZE_DP),
                            ).apply {
                                bottomMargin = dp(10)
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

                dialog.show()

                Plugin.getInstance()
                    .backgroundScope
                    .launch {
                        delay(POPUP_AUTO_DISMISS_MS.milliseconds)

                        runOnMainThread {
                            try {
                                if (dialog.isShowing)
                                    dialog.dismiss()
                            } catch (_: Throwable) {
                            }
                        }
                    }
            }

            if (result == null) {
                try {
                    releaseMedia()
                } finally {
                    onDismiss()
                }
            }
        }

        return true
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
        val fragment = LaunchActivity.getSafeLastFragment() as? ChatActivity
            ?: return null

        val dialogId = fragment.dialogId

        if (dialogId <= 0L)
            return null

        return OpenChatRelation(
            accountId = UserConfig.selectedAccount,
            peerUserId = dialogId
        )
    }

    private data class OpenChatRelation(
        val accountId: Int,
        val peerUserId: Long,
    )
}
