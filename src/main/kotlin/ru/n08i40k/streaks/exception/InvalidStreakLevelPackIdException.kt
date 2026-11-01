package ru.n08i40k.streaks.exception

class InvalidStreakLevelPackIdException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)