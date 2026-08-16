package ru.n08i40k.streaks.exception

class InvalidStreakEmojiPackIdException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)