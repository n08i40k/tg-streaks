package ru.n08i40k.streaks.data

import android.graphics.Color
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.streaks.Plugin

data class StreakViewData(
    val length: Int,
    val documentId: Long,
    val accentColor: Color,
    val isJubilee: Boolean
) {
    companion object {
        fun from(streak: Streak) = with(
            Plugin.getInstance()
                .streakEmojiPacksController
                .getCurrent()
                .getEmoji(streak.viewLevel)
        ) {
            StreakViewData(
                streak.length,
                documentId,
                accentColor
                    ?: Color.valueOf(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon)),
                streak.length == streak.level.length || streak.length % 100 == 0
            )
        }
    }
}
