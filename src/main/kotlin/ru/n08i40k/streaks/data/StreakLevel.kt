package ru.n08i40k.streaks.data

import android.graphics.Color
import ru.n08i40k.streaks.i18n.Strings

data class StreakLevel(
    val id: String,
    val length: Int,
    val documentId: Long,
    val accentColor: Color?
) : Comparable<StreakLevel> {
    val name: String
        get() = if (length == 0) Strings.streak_level_cold_name()
            else Strings.streak_level_days_name(length)

    override fun compareTo(other: StreakLevel): Int = length.compareTo(other.length)
}
