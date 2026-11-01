package ru.n08i40k.streaks.ui.upgrade

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.ImageReceiver
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BackupImageView
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.upgrade.Easing.hump
import ru.n08i40k.streaks.ui.upgrade.Easing.outQuad
import ru.n08i40k.streaks.ui.upgrade.Easing.smooth
import ru.n08i40k.streaks.ui.upgrade.UpgradeTimeline as T
import ru.n08i40k.streaks.util.colorBlend
import ru.n08i40k.streaks.util.dp
import ru.n08i40k.streaks.util.runOnMainThread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal class StreakUpgradeSceneView(
    context: Context,
    private val upgrade: StreakUpgrade,
    private val palette: StreakUpgradePalette,
) : FrameLayout(context) {
    private enum class State { Loading, Failed, Playing, Finished }

    private companion object {
        const val EMOJI_SIZE_DP = 160
        const val LOAD_TIMEOUT_MS = 20_000L

        val DEFAULT_ACCENT = Color.rgb(255, 154, 0)

        const val DESIGN_WIDTH_DP = 320
        const val DESIGN_HEIGHT_DP = 480

        const val ANCHOR_SHIFT_DP = 36

        const val PARTICLE_COUNT = 36
        const val LIGHT_STOPS = 18
        const val FLOAT_PERIOD_SECONDS = 2.4f

        val RINGS = arrayOf(Ring(T.BURST, 3f, 210), Ring(T.ECHO_RING, 1.5f, 120))
    }

    private class Ring(val span: Span, val strokeDp: Float, val opacity: Int)

    var onFinished: (() -> Unit)? = null

    var onFailed: (() -> Unit)? = null

    private val accountId = upgrade.accountId
    private val fromColor = upgrade.fromEmoji.accentColor?.toArgb() ?: DEFAULT_ACCENT
    private val toColor = upgrade.toEmoji.accentColor?.toArgb() ?: DEFAULT_ACCENT
    private val text = StreakUpgradeTextRenderer(
        upgrade.from.length, upgrade.to.length, fromColor, toColor, palette, StreakUpgradeFont.get(),
    )
    private val avatars = StreakUpgradeAvatarsRenderer(this, accountId, upgrade.peer, fromColor, toColor)

    private val oldEmoji = createEmojiView()
    private val newEmoji = createEmojiView()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(16).toFloat()
        color = palette.secondaryText
    }
    private val glowRadius = dp(200).toFloat()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = softFalloff(glowRadius) }
    private val lightLayers = arrayOf(
        LightLayer(dp(250).toFloat(), seed = 1.3f, degreesPerSecond = 9f, strength = 1f),
        LightLayer(dp(200).toFloat(), seed = 7.1f, degreesPerSecond = -6f, strength = .8f),
    )
    private var tintColor = 0
    private var tint: PorterDuffColorFilter? = null

    private val particleCos = FloatArray(PARTICLE_COUNT)
    private val particleSin = FloatArray(PARTICLE_COUNT)
    private val particleReach = FloatArray(PARTICLE_COUNT)
    private val particleSize = FloatArray(PARTICLE_COUNT)

    private var state = State.Loading
    private var time = 0f
    private var ambient = 0f
    private var pendingEmojis = 2
    private val haptics = StreakUpgradeHaptics(context)
    private var lastFrameTime = 0f
    private var lastCountValue = upgrade.from.length
    private var clock: TimeAnimator? = null
    private var released = false

    private val loadTimeout = Runnable { if (state == State.Loading) fail() }

    init {
        repeat(PARTICLE_COUNT) { i ->
            val angle = i * 2 * PI / PARTICLE_COUNT + .17 * sin(i.toDouble())
            particleCos[i] = cos(angle).toFloat()
            particleSin[i] = sin(angle).toFloat()
            particleReach[i] = dp(110 + i % 5 * 14).toFloat()
            particleSize[i] = dp(1 + i % 3).toFloat()
        }
        contentDescription = Strings.upgrade_scene_description(upgrade.to.length)
    }

    private fun createEmojiView() = BackupImageView(context).also {
        it.setAspectFit(true)
        it.alpha = 0f
        it.imageReceiver.currentAccount = accountId
        addView(it, LayoutParams(dp(EMOJI_SIZE_DP), dp(EMOJI_SIZE_DP), Gravity.CENTER).apply {
            bottomMargin = dp(ANCHOR_SHIFT_DP)
        })
    }

    private fun softFalloff(radius: Float) = RadialGradient(
        0f, 0f, radius,
        intArrayOf(Color.WHITE, Color.argb(150, 255, 255, 255), Color.argb(45, 255, 255, 255), Color.TRANSPARENT),
        floatArrayOf(0f, .3f, .65f, 1f), Shader.TileMode.CLAMP,
    )

    private inner class LightLayer(
        val radius: Float,
        seed: Float,
        val degreesPerSecond: Float,
        val strength: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val colors = IntArray(LIGHT_STOPS)
            val positions = FloatArray(LIGHT_STOPS)
            for (i in 0 until LIGHT_STOPS) {
                val noise = sin(i * 12.9898f + seed * 78.233f) * 43758.547f
                val random = noise - kotlin.math.floor(noise)
                val brightness = .08f + .92f * random * random
                colors[i] = Color.argb((brightness * 255).toInt(), 255, 255, 255)
                positions[i] = i / (LIGHT_STOPS - 1f)
            }
            colors[LIGHT_STOPS - 1] = colors[0]
            shader = ComposeShader(
                SweepGradient(0f, 0f, colors, positions),
                softFalloff(radius),
                PorterDuff.Mode.DST_IN,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released)
            return
        avatars.attach()
        if (state != State.Loading)
            return

        postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        loadEmoji(oldEmoji, upgrade.fromEmoji, fromColor)
        loadEmoji(newEmoji, upgrade.toEmoji, toColor)
    }

    private fun loadEmoji(view: BackupImageView, info: StreakEmojiInfo, color: Int) {
        var ready = false
        view.imageReceiver.setDelegate(object : ImageReceiver.ImageReceiverDelegate {
            override fun didSetImageBitmap(type: Int, key: String?, drawable: Drawable?) = Unit

            override fun onAnimationReady(receiver: ImageReceiver?) = Unit

            override fun didSetImage(receiver: ImageReceiver, set: Boolean, thumb: Boolean, memCache: Boolean) {
                if (released || ready || !set || thumb || !receiver.hasNotThumb())
                    return
                ready = true
                pendingEmojis--
                post { tryPlay() }
            }
        })
        AnimatedEmojiDrawable.getDocumentFetcher(accountId).fetchDocument(info.documentId) { document ->
            runOnMainThread {
                if (released)
                    return@runOnMainThread
                if (document == null) {
                    fail()
                    return@runOnMainThread
                }
                if (AnimatedEmojiDrawable.make(accountId, 0, document).canOverrideColor())
                    view.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
                val size = EMOJI_SIZE_DP
                val filter = if (document.mime_type == "video/webm") "${size}_${size}_${ImageLoader.AUTOPLAY_FILTER}" else "${size}_${size}"
                view.setImage(ImageLocation.getForDocument(document), filter, null, document)
            }
        }
    }

    private fun fail() {
        if (state != State.Loading)
            return
        state = State.Failed
        removeCallbacks(loadTimeout)
        invalidate()
        onFailed?.invoke()
    }

    private fun tryPlay() {
        if (released || state != State.Loading || pendingEmojis > 0)
            return
        removeCallbacks(loadTimeout)
        state = State.Playing

        if (!ValueAnimator.areAnimatorsEnabled()) {
            time = T.DURATION
            ambient = T.DURATION
            lastFrameTime = T.DURATION
            onFrame()
            state = State.Finished
            onFinished?.invoke()
            return
        }

        clock = TimeAnimator().apply {
            setTimeListener { _, totalTime, _ ->
                ambient = totalTime / 1000f
                time = minOf(ambient, T.DURATION)
                onFrame()
                if (state == State.Playing && ambient >= T.DURATION) {
                    state = State.Finished
                    onFinished?.invoke()
                }
            }
            start()
        }
    }

    private fun onFrame() {
        playHaptics()

        val intro = smooth(T.INTRO.at(time))
        val charge = smooth(T.OLD_CHARGE.at(time))
        val oldScale = (.8f + .2f * intro) * (1 - .35f * charge)
        oldEmoji.alpha = intro * (1 - T.OLD_OUT.at(time))
        oldEmoji.scaleX = oldScale
        oldEmoji.scaleY = oldScale
        oldEmoji.rotation = Easing.noise(time * 45f) * charge * 7f
        oldEmoji.translationX = dp(3) * charge * Easing.noise(time * 61f + 11f)
        oldEmoji.translationY = dp(3) * charge * Easing.noise(time * 53f + 17f)

        val spring = Easing.spring(T.NEW_SPRING.at(time), 6f, 10f)
        newEmoji.alpha = T.NEW_IN.at(time)
        newEmoji.scaleX = spring
        newEmoji.scaleY = spring
        val float = sin((ambient - T.FLOAT_IN.start) * 2 * PI.toFloat() / FLOAT_PERIOD_SECONDS)
        newEmoji.translationY = -dp(4) * smooth(T.FLOAT_IN.at(time)) * (.5f + .5f * float)

        invalidate()
    }

    private fun playHaptics() {
        val previous = lastFrameTime
        lastFrameTime = time
        fun crossed(moment: Float) = previous < moment && time >= moment

        val count = text.countValue(time)
        if (count != lastCountValue && !T.isUpgraded(time))
            haptics.tick(smooth(T.OLD_CHARGE.at(time)))
        lastCountValue = count

        if (crossed(T.SWAP))
            haptics.burst()
        if (crossed(T.SHEEN.start))
            haptics.tick(.35f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (state == State.Loading || state == State.Failed) {
            val status = if (state == State.Failed) Strings.upgrade_scene_load_failed() else Strings.upgrade_scene_loading()
            canvas.drawText(status, cx, cy, statusPaint)
            return
        }

        val scale = minOf(1f, width / dp(DESIGN_WIDTH_DP).toFloat(), height / dp(DESIGN_HEIGHT_DP).toFloat())
        val anchorY = cy - dp(ANCHOR_SHIFT_DP)
        val charge = smooth(T.OLD_CHARGE.at(time))
        val rumble = if (T.isUpgraded(time)) 0f else dp(2.5f) * charge * charge
        val hit = if (T.SHAKE.isActive(time)) 1 - outQuad(T.SHAKE.at(time)) else 0f
        val shake = rumble + dp(9) * hit

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.translate(shake * Easing.noise(time * 55f), shake * .7f * Easing.noise(time * 48f + 3f))
        canvas.rotate(1.8f * hit * Easing.noise(time * 40f + 7f), cx, anchorY)

        val colorShift = smooth(T.COLOR_SHIFT.at(time))
        val color = colorBlend(fromColor, toColor, colorShift)
        drawGlow(canvas, cx, anchorY, color)
        drawRings(canvas, cx, anchorY, color)
        drawParticles(canvas, cx, anchorY, color)
        avatars.draw(canvas, cx, anchorY, time)

        super.dispatchDraw(canvas)

        drawFlash(canvas, cx, anchorY)
        text.draw(canvas, cx, anchorY, width / scale, time)
        canvas.restore()
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        if (color != tintColor || tint == null) {
            tintColor = color
            tint = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        val burst = T.BURST.at(time)
        val envelope = (40 + 45 * smooth(T.OLD_CHARGE.at(time)) + 120 * hump(burst)) * palette.glowStrength
        val kick = 35f * outQuad(burst)

        canvas.save()
        canvas.translate(cx, cy)
        glowPaint.colorFilter = tint
        glowPaint.alpha = (envelope * .7f).toInt()
        canvas.drawCircle(0f, 0f, glowRadius, glowPaint)
        for (layer in lightLayers) {
            val direction = if (layer.degreesPerSecond < 0) -1 else 1
            canvas.save()
            canvas.rotate(ambient * layer.degreesPerSecond + kick * direction)
            layer.paint.colorFilter = tint
            layer.paint.alpha = (envelope * layer.strength).toInt().coerceAtMost(255)
            canvas.drawCircle(0f, 0f, layer.radius, layer.paint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        for (ring in RINGS) {
            if (!ring.span.isActive(time))
                continue
            val progress = outQuad(ring.span.at(time))
            paint.strokeWidth = dp(ring.strokeDp) * (1 - progress)
            paint.alpha = ((1 - progress) * ring.opacity).toInt()
            canvas.drawCircle(cx, cy, dp(65) + dp(125) * progress, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val upgraded = T.isUpgraded(time)
        val charge = smooth(T.PARTICLES_CHARGE.at(time))
        val burst = T.BURST.at(time)
        val fall = dp(25) * burst * burst
        paint.color = color
        paint.alpha = ((if (upgraded) 1 - burst else charge) * 220).toInt()
        for (i in 0 until PARTICLE_COUNT) {
            val radius =
                if (upgraded) dp(25) + particleReach[i] * outQuad(burst)
                else dp(170) - dp(145) * charge
            val twinkle = .75f + .25f * sin(time * 14f + i * 1.7f)
            canvas.drawCircle(
                cx + particleCos[i] * radius,
                cy + particleSin[i] * radius + fall,
                particleSize[i] * twinkle,
                paint,
            )
        }
    }

    private fun drawFlash(canvas: Canvas, cx: Float, cy: Float) {
        val flash = (1 - abs(time - T.SWAP) / T.FLASH_HALF_WIDTH).coerceIn(0f, 1f)
        if (flash <= 0f)
            return
        paint.color = Color.WHITE
        paint.alpha = (flash * 245).toInt()
        canvas.drawCircle(cx, cy, dp(100) * (.7f + flash * .3f), paint)
    }

    fun release() {
        if (released)
            return
        released = true
        onFinished = null
        onFailed = null
        removeCallbacks(loadTimeout)
        clock?.cancel()
        clock = null
        haptics.cancel()
        avatars.detach()
        for (view in listOf(oldEmoji, newEmoji)) {
            view.imageReceiver.setDelegate(null)
            view.clearImage()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val clock = clock ?: return
        if (visibility == VISIBLE) clock.resume() else clock.pause()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
