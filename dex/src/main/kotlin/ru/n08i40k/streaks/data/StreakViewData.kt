package ru.n08i40k.streaks.data

import android.graphics.Color

data class StreakViewData(
    val length: Int,
    val documentId: Long,
    val accentColor: Color,
    val isJubilee: Boolean
) {
    companion object {
        fun from(streak: Streak) = StreakViewData(
            streak.length,
            streak.level.documentId,
            streak.level.color,
            streak.length == streak.level.length || streak.length % 100 == 0
        )
    }
}
