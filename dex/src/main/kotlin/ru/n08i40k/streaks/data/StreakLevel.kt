package ru.n08i40k.streaks.data

import android.graphics.Color

data class StreakLevel(
    val length: Int,
    val color: Color,
    val documentId: Long,
    val popupResourceName: String,
) {
    val colorInt: Int = color.toArgb()
}
