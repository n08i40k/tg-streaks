package ru.n08i40k.streaks.ui

import ru.n08i40k.streaks.data.StreakPetLevel.Companion.levels

internal object StreakPetPresentation {
    data class Progress(val points: Int, val target: Int, val locked: Boolean, val max: Boolean) {
        val fraction: Float get() = if (locked) 0f else points.toFloat() / target
    }

    fun progress(points: Int, selected: Int): Progress {
        val previous = if (selected > 0) levels[selected - 1].maxPoints else 0
        val target = levels[selected].maxPoints - previous
        return Progress(
            (points - previous).coerceIn(0, target), target,
            selected > 0 && points < previous,
            selected == levels.lastIndex && points >= levels.last().maxPoints,
        )
    }
}
