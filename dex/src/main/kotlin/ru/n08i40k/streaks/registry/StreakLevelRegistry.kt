package ru.n08i40k.streaks.registry

import ru.n08i40k.streaks.data.StreakLevel

class StreakLevelRegistry {
    private val _levels = sortedSetOf<StreakLevel>(compareBy { it.length })

    fun levels(): List<StreakLevel> =
        _levels.toList()

    fun findByLengthApproximate(length: Int): StreakLevel =
        _levels.findLast { it.length <= length } ?: _levels.last()

    fun findByLengthPrecise(length: Int): StreakLevel? =
        _levels.find { it.length == length }

    // Plugin.clearCaches should be called after all regs
    fun register(level: StreakLevel) {
        if (findByLengthPrecise(level.length) != null)
            throw UnsupportedOperationException("Unable to overwrite existing streak")

        _levels.add(level)
    }
}
