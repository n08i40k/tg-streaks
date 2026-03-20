package ru.n08i40k.streaks.chat_history_fetcher

import java.time.LocalDate


interface ChatHistoryFetcher {
    sealed class Status(val wasRevived: Boolean) {
        class NoActivity : Status(false)
        class FromOwner(wasRevived: Boolean) : Status(wasRevived)
        class FromPeer(wasRevived: Boolean) : Status(wasRevived)
        class FromBoth(wasRevived: Boolean) : Status(wasRevived)
    }

    suspend fun fetch(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        untilRevive: Boolean = false
    ): Status
}