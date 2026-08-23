package ru.n08i40k.streaks.chat_history_fetcher

import org.telegram.tgnet.TLRPC
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant


interface ChatHistoryFetcher {
    sealed class Status(val wasRestored: Boolean) {
        class NoActivity(wasRestored: Boolean) : Status(wasRestored)
        class FromOwner(wasRestored: Boolean) : Status(wasRestored)
        class FromPeer(wasRestored: Boolean) : Status(wasRestored)
        class FromBoth(wasRestored: Boolean) : Status(wasRestored)
    }

    data class DayActivity(
        val status: Status,
        val lastOwnerAt: Instant?,
        val lastPeerAt: Instant?,
    )

    suspend fun fetchActivity(
        accountId: Int,
        peerUserId: Long,
        timeZone: TimeZone,
        day: LocalDate,
        untilRestore: Boolean = false
    ): DayActivity

    suspend fun fetchRawMessages(
        accountId: Int,
        peerUserId: Long,
        timeZone: TimeZone,
        day: LocalDate,
        fromOwnerMax: Int,
        fromPeerMax: Int,
    ): List<TLRPC.Message>
}
