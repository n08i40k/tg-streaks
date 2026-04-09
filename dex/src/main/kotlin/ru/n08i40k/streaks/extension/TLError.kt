package ru.n08i40k.streaks.extension

import org.telegram.tgnet.TLRPC

private val FLOOD_WAIT_REGEX = Regex("""FLOOD_WAIT_(\d+)""")
private val MSG_WAIT_ERRORS = listOf("MSG_WAIT_TIMEOUT", "MSG_WAIT_FAILED")

fun TLRPC.TL_error.isRateLimited(): Boolean {
    val text = this.text.orEmpty()

    return text.startsWith("FLOOD_WAIT_")
            || text.contains("RATE_LIMIT", ignoreCase = true)
            || text.contains("TOO_MANY_REQUESTS", ignoreCase = true)
            || code == 429
}

fun TLRPC.TL_error.isTransientFailure(): Boolean {
    val text = this.text.orEmpty()

    return MSG_WAIT_ERRORS.any { text.contains(it, ignoreCase = true) }
            || text.startsWith("MSG_WAIT_", ignoreCase = true)
}

fun TLRPC.TL_error.isPeerIdInvalid(): Boolean {
    val text = this.text.orEmpty()

    return (code == 400 && text.startsWith("PEER_ID_INVALID"))
            || text.contains("PEER_ID_INVALID", ignoreCase = true)
}

fun TLRPC.TL_error.retryDelayMs(minDelay: Long): Long {
    val floodWaitSeconds = FLOOD_WAIT_REGEX.find(this.text.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

    return maxOf(minDelay, (floodWaitSeconds ?: 0L) * 1000L)
}

fun TLRPC.TL_error.fmt(): String = "[${this.code}] ${this.text}"
