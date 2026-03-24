package ru.n08i40k.streaks.data

data class StreakPetLevel(
    val maxPoints: Int,
    val imageResourcePath: String,
    val gradientStart: String,
    val gradientEnd: String,
    val petStart: String,
    val petEnd: String,
    val accent: String,
    val accentSecondary: String,
)
