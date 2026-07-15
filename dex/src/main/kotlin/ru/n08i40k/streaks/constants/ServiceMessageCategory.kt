package ru.n08i40k.streaks.constants

object ServiceMessageCategory {
    const val LIFECYCLE = "lifecycle"
    const val LEVEL_UP = "streak-upgrades"
    const val PET = "pet"

    val all = listOf(LIFECYCLE, LEVEL_UP, PET)
}
