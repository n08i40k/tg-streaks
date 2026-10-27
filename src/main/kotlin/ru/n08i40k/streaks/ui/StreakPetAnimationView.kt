package ru.n08i40k.streaks.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import org.telegram.messenger.ImageReceiver
import org.telegram.ui.Components.AnimatedFileDrawable
import ru.n08i40k.streaks.data.StreakPetLevel
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RuntimeGuard

@android.annotation.SuppressLint("ViewConstructor")
internal class StreakPetAnimationView(
    context: Context,
    private val resourcesProvider: ResourcesProvider,
    private val accountId: Int,
) : View(context), Application.ActivityLifecycleCallbacks {
    private val application = context.applicationContext as Application
    private var stage: StreakPetLevel? = null
    private var animation: AnimatedFileDrawable? = null
    private var foreground = false
    private var released = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var placeholderColor = Color.parseColor("#F9B746")
    private var placeholderAccentColor = Color.parseColor("#8D4A00")

    // Telegram's decoder starts only while it has an ImageReceiver parent.
    private val animationParent = ImageReceiver(this).apply { currentAccount = accountId }

    fun bind(level: StreakPetLevel) {
        if (released || stage == level)
            return

        disposeDecoder()
        stage = level
        placeholderColor = Color.parseColor(level.petStart)
        placeholderAccentColor = Color.parseColor(level.accent)

        if (isAttachedToWindow)
            load()

        invalidate()
    }

    private fun load() {
        if (released || animation != null)
            return

        val level = stage
            ?: return

        val file = resourcesProvider.resolveResource("streak-pet/${level.imageResourcePath}")
            ?: return

        animation = runCatching {
            // createDecoder=false: opening and decoding the media happens on the worker queue.
            AnimatedFileDrawable(
                file, false, 0L, 0, null, null, null, 0L,
                accountId, false, null
            ).apply {
                setIsWebmSticker(true)
                setParentView(this@StreakPetAnimationView)
                addParent(animationParent)
                setUseSharedQueue(true)
            }
        }
            .onFailure { Logger.info("[PetMedia] Failed to open ${file.name}: $it") }
            .getOrNull()

        updatePlayback()
    }

    private fun updatePlayback() {
        if (isAttachedToWindow && foreground && windowVisibility == VISIBLE && isShown && !released)
            animation?.start()
        else
            animation?.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (released)
            return

        foreground = RuntimeGuard.isAppForeground()
        application.registerActivityLifecycleCallbacks(this)
        animationParent.onAttachedToWindow()

        load()
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        application.unregisterActivityLifecycleCallbacks(this)
        disposeDecoder()
        animationParent.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlayback()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePlayback()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val drawable = animation

        if (drawable != null && !drawable.decoderFailed()) {
            val side = minOf(width, height)
            val left = (width - side) / 2
            val top = (height - side) / 2

            animationParent.setImageCoords(
                left.toFloat(),
                top.toFloat(),
                side.toFloat(),
                side.toFloat()
            )

            drawable.apply {
                setBounds(left, top, left + side, top + side)
                draw(canvas)

                if (hasBitmap())
                    return
            }
        } else if (drawable != null) {
            Logger.info("[PetMedia] Failed to decode ${stage?.imageResourcePath}")
            disposeDecoder()
        }

        // A small native silhouette also covers missing media without disabling the screen.
        paint.color = placeholderColor
        val radius = minOf(width, height) * .18f
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)

        paint.color = placeholderAccentColor
        canvas.drawCircle(width / 2f - radius * .3f, height / 2f, radius * .08f, paint)
        canvas.drawCircle(width / 2f + radius * .3f, height / 2f, radius * .08f, paint)
    }

    private fun disposeDecoder() {
        animation?.apply {
            stop()
            removeParent(animationParent)
            setParentView(null)
            recycle()
        }

        animation = null
    }

    fun release() {
        released = true
        application.unregisterActivityLifecycleCallbacks(this)
        disposeDecoder()
        animationParent.onDetachedFromWindow()
    }

    override fun onActivityResumed(activity: Activity) {
        foreground = true; updatePlayback()
    }

    override fun onActivityPaused(activity: Activity) {
        foreground = false; updatePlayback()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
