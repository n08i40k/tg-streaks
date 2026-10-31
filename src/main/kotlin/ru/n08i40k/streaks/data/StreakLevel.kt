package ru.n08i40k.streaks.data

import kotlinx.collections.immutable.toImmutableList
import ru.n08i40k.streaks.i18n.Strings

enum class StreakLevel(
    val id: String,
    val length: Int,
) {
    Cold("cold", 0),
    Days3("3d", 3),
    Days10("10d", 10),
    Days30("30d", 30),
    Days100("100d", 100),
    Days200("200d", 200);

    companion object {
        val levels = sortedSetOf(
            compareBy { it.length },
            Cold,
            Days3,
            Days10,
            Days30,
            Days100,
            Days200
        ).toImmutableList()

        fun findNext(level: StreakLevel): StreakLevel? =
            levels.firstOrNull { it.length > level.length }

        fun findPrevious(level: StreakLevel): StreakLevel? =
            levels.lastOrNull { it.length < level.length }

        fun findById(id: String): StreakLevel? =
            levels.firstOrNull { it.id == id }

        fun findFirstVisible(): StreakLevel =
            levels.first { it.length >= Streak.MIN_VISIBLE_LENGTH }

        fun findByLengthApproximate(length: Int): StreakLevel =
            levels.findLast { it.length <= length } ?: levels.last()

        fun nameById(id: String): String = when (id) {
            "cold" -> Strings.streak_level_cold_name()
            "3d" -> Strings.streak_level_3d_name()
            "10d" -> Strings.streak_level_10d_name()
            "30d" -> Strings.streak_level_30d_name()
            "100d" -> Strings.streak_level_100d_name()
            "200d" -> Strings.streak_level_200d_name()
            else -> id
        }
    }
}
