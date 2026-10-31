package ru.n08i40k.streaks.ui.upgrade

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AvatarDrawable
import ru.n08i40k.streaks.ui.upgrade.Easing.hump
import ru.n08i40k.streaks.ui.upgrade.Easing.outQuad
import ru.n08i40k.streaks.ui.upgrade.Easing.smooth
import ru.n08i40k.streaks.ui.upgrade.UpgradeTimeline as T
import ru.n08i40k.streaks.util.colorBlend
import ru.n08i40k.streaks.util.dp

internal class StreakUpgradeAvatarsRenderer(
    parent: View,
    accountId: Int,
    peer: TLRPC.User?,
    private val fromColor: Int,
    private val toColor: Int,
) {
    private companion object {
        const val SIZE_DP = 46
        const val Y_DP = -172
        const val SPREAD_DP = 19
        const val ENTRY_SPREAD_DP = 40
        const val PULL_DP = 7
        const val BOUNCE_DP = 10
        const val RING_GAP_DP = 2.5f
        const val WAVE_REACH_DP = 18
    }

    private val receivers: List<ImageReceiver> =
        listOfNotNull(UserConfig.getInstance(accountId).currentUser, peer).map { user ->
            ImageReceiver(parent).apply {
                currentAccount = accountId
                setRoundRadius(dp(SIZE_DP / 2))
                setForUserOrChat(user, AvatarDrawable().apply { setInfo(accountId, user) })
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }

    fun attach() = receivers.forEach { it.onAttachedToWindow() }

    fun detach() = receivers.forEach { it.onDetachedFromWindow() }

    fun draw(canvas: Canvas, cx: Float, cy: Float, time: Float) {
        if (receivers.isEmpty())
            return

        val entry = outQuad(T.AVATARS_IN.at(time))
        val pull = smooth(T.OLD_CHARGE.at(time)) * (1 - smooth(T.AVATARS_BOUNCE.at(time)))
        val bounce = T.AVATARS_BOUNCE.at(time).let { hump(it) * (1 - it) }
        val spread =
            if (receivers.size == 1) 0f
            else dp(SPREAD_DP) + dp(ENTRY_SPREAD_DP) * (1 - entry) - dp(PULL_DP) * pull + dp(BOUNCE_DP) * bounce
        val scale = (.7f + .3f * entry) * (1 + .15f * bounce)
        val alpha = smooth(T.AVATARS_IN.at(time))
        val radius = dp(SIZE_DP) / 2f * scale
        val ringColor = colorBlend(fromColor, toColor, smooth(T.COLOR_SHIFT.at(time)))
        val baseY = cy + dp(Y_DP)
        val jitter = dp(2) * smooth(T.OLD_CHARGE.at(time)) * (1 - smooth(T.AVATARS_BOUNCE.at(time)))

        receivers.forEachIndexed { index, receiver ->
            val x = cx + (if (receivers.size == 1) 0f else if (index == 0) -spread else spread) +
                jitter * Easing.noise(time * 50f + index * 5f)
            val y = baseY + jitter * Easing.noise(time * 43f + index * 9f)

            if (T.AVATARS_WAVE.isActive(time)) {
                val wave = T.AVATARS_WAVE.at(time)
                ringPaint.color = toColor
                ringPaint.alpha = ((1 - wave) * 200).toInt()
                canvas.drawCircle(x, y, radius + dp(RING_GAP_DP) + dp(WAVE_REACH_DP) * outQuad(wave), ringPaint)
            }

            ringPaint.color = ringColor
            ringPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(x, y, radius + dp(RING_GAP_DP), ringPaint)

            receiver.setAlpha(alpha)
            receiver.setImageCoords(x - radius, y - radius, radius * 2, radius * 2)
            receiver.draw(canvas)
        }
    }
}
