package ru.n08i40k.streaks.registry

import ru.n08i40k.streaks.data.StreakPetLevel

class StreakPetLevelRegistry {
    private val levels = sortedSetOf<StreakPetLevel>(compareBy { it.maxPoints })

    fun levels(): List<StreakPetLevel> =
        levels.toList()

    fun findByMaxPointsPrecise(maxPoints: Int): StreakPetLevel? =
        levels.find { it.maxPoints == maxPoints }

    fun register(level: StreakPetLevel) {
        if (findByMaxPointsPrecise(level.maxPoints) != null)
            throw UnsupportedOperationException("Unable to overwrite existing streak pet level")

        levels.add(level)
    }
}
