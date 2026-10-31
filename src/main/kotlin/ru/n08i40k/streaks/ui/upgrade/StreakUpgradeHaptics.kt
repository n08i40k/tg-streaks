package ru.n08i40k.streaks.ui.upgrade

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

internal class StreakUpgradeHaptics(context: Context) {
    private companion object {
        const val TICK_MS = 8L
        const val MIN_TICK_INTERVAL_MS = 35L
        const val WEAKEST_TICK = 25
        const val STRONGEST_TICK = 220

        val BURST_TIMINGS = longArrayOf(0, 45, 25, 35, 25, 30, 30, 25, 35, 20)
        val BURST_AMPLITUDES = intArrayOf(0, 255, 0, 190, 0, 130, 0, 80, 0, 40)
    }

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val enabled = vibrator?.hasVibrator() == true &&
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true
    private var lastTickAt = 0L

    fun tick(strength: Float) {
        if (!enabled)
            return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < MIN_TICK_INTERVAL_MS)
            return
        lastTickAt = now
        val amplitude =
            if (amplitudeControl) (WEAKEST_TICK + (STRONGEST_TICK - WEAKEST_TICK) * strength.coerceIn(0f, 1f)).toInt()
            else VibrationEffect.DEFAULT_AMPLITUDE
        vibrator?.vibrate(VibrationEffect.createOneShot(TICK_MS, amplitude))
    }

    fun burst() {
        if (!enabled)
            return
        val effect =
            if (amplitudeControl) VibrationEffect.createWaveform(BURST_TIMINGS, BURST_AMPLITUDES, -1)
            else VibrationEffect.createOneShot(BURST_TIMINGS[1], VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator?.vibrate(effect)
    }

    fun cancel() {
        if (enabled)
            vibrator?.cancel()
    }
}
