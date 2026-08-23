package ru.n08i40k.streaks.data

import kotlinx.collections.immutable.toImmutableList

enum class StreakPetLevel(
    val maxPoints: Int,
    val imageResourcePath: String,
    val gradientStart: String,
    val gradientEnd: String,
    val petStart: String,
    val petEnd: String,
    val accent: String,
    val accentSecondary: String,
) {
    Points100(
        100,
        "points-100.webm",
        "#F9B746",
        "#FFF8E8",
        "#FFCB68",
        "#FF9C24",
        "#8D4A00",
        "#FFF2C8",
    ),
    Points300(
        300,
        "points-300.webm",
        "#FEA386",
        "#FFF2EC",
        "#FFC0A9",
        "#F9724F",
        "#8A2E19",
        "#FFE1D6",
    ),
    Points500(
        500,
        "points-500.webm",
        "#FF8EFA",
        "#FFF0FF",
        "#FFB6FC",
        "#FF63E3",
        "#842C7A",
        "#FFE3FB",
    ),
    Points900(
        900,
        "points-900.webm",
        "#6873FF",
        "#EEF0FF",
        "#98A1FF",
        "#4A56F0",
        "#2230A3",
        "#DFE3FF",
    );

    companion object {
        val levels = sortedSetOf(
            compareBy { it.maxPoints },
            Points100,
            Points300,
            Points500,
            Points900
        ).toImmutableList()

        fun findByPointsApproximate(points: Int): StreakPetLevel =
            levels.find { points < it.maxPoints } ?: levels.last()
    }
}
