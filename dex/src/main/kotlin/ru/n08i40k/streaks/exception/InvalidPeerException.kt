package ru.n08i40k.streaks.exception

class InvalidPeerException(
    val accountId: Int,
    val peerUserId: Long,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
