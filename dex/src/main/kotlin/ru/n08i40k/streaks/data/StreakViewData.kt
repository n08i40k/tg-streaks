package ru.n08i40k.streaks.data

import android.graphics.Color

data class StreakViewData(
    val length: Int,
    val documentId: Long,
    val accentColor: Color,
    val isJubilee: Boolean
)
