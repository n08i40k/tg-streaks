package ru.n08i40k.streaks.ui.upgrade

import android.graphics.Color
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

internal class StreakUpgradePalette(
    val background: Int,
    val text: Int,
    val secondaryText: Int,
) {
    val isDark = AndroidUtilities.computePerceivedBrightness(background) < .5f

    val ink = if (isDark) Color.WHITE else text

    val scrim = (background and 0x00FFFFFF) or (SCRIM_ALPHA shl 24)

    val glowStrength = if (isDark) 1f else .6f

    companion object {
        private const val SCRIM_ALPHA = 218

        fun fromTheme() = StreakUpgradePalette(
            background = Theme.getColor(Theme.key_windowBackgroundWhite),
            text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
            secondaryText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
        )
    }
}
