package ru.n08i40k.streaks.data

import android.graphics.Color

data class StreakLevel(
    val length: Int,
    val color: Color,
    val documentId: Long,
    val popupResourceName: String,
) : Comparable<StreakLevel> {
    val colorInt: Int = color.toArgb()

    override fun compareTo(other: StreakLevel): Int =
        length - other.length
}
