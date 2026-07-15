package ru.n08i40k.streaks.chat_history_fetcher

import org.telegram.tgnet.TLRPC
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant


interface ChatHistoryFetcher {
    sealed class Status(val wasRevived: Boolean) {
        class NoActivity(wasRevived: Boolean) : Status(wasRevived)
        class FromOwner(wasRevived: Boolean) : Status(wasRevived)
        class FromPeer(wasRevived: Boolean) : Status(wasRevived)
        class FromBoth(wasRevived: Boolean) : Status(wasRevived)
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
        untilRevive: Boolean = false
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
