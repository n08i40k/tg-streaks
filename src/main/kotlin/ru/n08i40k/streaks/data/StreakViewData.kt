package ru.n08i40k.streaks.data

import android.graphics.Color
import org.telegram.ui.ActionBar.Theme

data class StreakViewData(
    val length: Int,
    val documentId: Long,
    val accentColor: Color,
    val isJubilee: Boolean
) {
    companion object {
        fun from(streak: Streak) = with(streak.viewLevel) {
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
