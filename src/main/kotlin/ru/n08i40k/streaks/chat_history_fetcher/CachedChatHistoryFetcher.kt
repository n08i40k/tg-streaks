package ru.n08i40k.streaks.chat_history_fetcher

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSeconds
import ru.n08i40k.streaks.ui.AccountCacheReader
import kotlin.time.Instant

class CachedChatHistoryFetcher : ChatHistoryFetcher {
    override suspend fun fetchActivity(
        accountId: Int,
        peerUserId: Long,
        timeZone: TimeZone,
        day: LocalDate,
        untilRestore: Boolean
    ): ChatHistoryFetcher.DayActivity {
        val startLocalEpoch = day.toEpochSeconds(timeZone)
        val endLocalEpoch = day.next().toEpochSeconds(timeZone)

        var fromOwner = false
        var fromPeer = false
        var wasRestored = false
        var lastOwnerAt: Instant? = null
        var lastPeerAt: Instant? = null

        val historyIterator = AccountCacheReader.getHistoryCursor(
            accountId,
            startLocalEpoch,
            endLocalEpoch,
            peerUserId
        )

        for (message in historyIterator) {
            if (message.message == ServiceMessage.RESTORE_TEXT) {
                wasRestored = true

                @Suppress("KotlinConstantConditions")
                if (fromOwner && fromPeer && untilRestore)
                    break

                continue
            }

            if (ServiceMessage.isServiceText(message.message))
                continue

            if (message.out) {
                fromOwner = true
                if (lastOwnerAt == null)
                    lastOwnerAt = Instant.fromEpochSeconds(message.date.toLong())
            } else {
                fromPeer = true
                if (lastPeerAt == null)
                    lastPeerAt = Instant.fromEpochSeconds(message.date.toLong())
            }

            if (fromOwner && fromPeer && (!untilRestore || wasRestored))
                break
        }

        val status = when {
            fromOwner && fromPeer -> ChatHistoryFetcher.Status.FromBoth(wasRestored)
            fromOwner -> ChatHistoryFetcher.Status.FromOwner(wasRestored)
            fromPeer -> ChatHistoryFetcher.Status.FromPeer(wasRestored)
            else -> ChatHistoryFetcher.Status.NoActivity(wasRestored)
        }

        return ChatHistoryFetcher.DayActivity(status, lastOwnerAt, lastPeerAt)
    }

    override suspend fun fetchRawMessages(
        accountId: Int,
        peerUserId: Long,
        timeZone: TimeZone,
        day: LocalDate,
        fromOwnerMax: Int,
        fromPeerMax: Int,
    ): List<TLRPC.Message> {
        val startLocalEpoch = day.toEpochSeconds(timeZone)
        val endLocalEpoch = day.next().toEpochSeconds(timeZone)

        val messages = mutableListOf<TLRPC.Message>()
        var fromOwnerCount = 0
        var fromPeerCount = 0

        val historyIterator = AccountCacheReader.getHistoryCursor(
            accountId,
            startLocalEpoch,
            endLocalEpoch,
            peerUserId
        )

        for (message in historyIterator) {
            messages.add(message)

            if (message.out)
                ++fromOwnerCount
            else
                ++fromPeerCount

            if (fromOwnerCount >= fromOwnerMax && fromPeerCount >= fromPeerMax)
                break
        }

        return messages.toList()
    }
}
