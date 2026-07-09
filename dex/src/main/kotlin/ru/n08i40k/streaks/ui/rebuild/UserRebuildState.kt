package ru.n08i40k.streaks.ui.rebuild

import org.telegram.tgnet.TLRPC

sealed interface UserRebuildState {
    val user: TLRPC.User

    data class Pending(
        override val user: TLRPC.User
    ) : UserRebuildState

    data class InProcess(
        override val user: TLRPC.User,
        val daysIndexed: Int,
        // null if indexing is working normally
        // 1 of 10 seconds when throttling
        val throttlingClock: Pair<Int, Int>?
    ) : UserRebuildState

    data class Done<T>(
        override val user: TLRPC.User,
        val record: T?
    ) : UserRebuildState
}
