package ru.n08i40k.streaks.chat_history_fetcher

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSeconds
import kotlin.time.Instant

class CachedChatHistoryFetcher : ChatHistoryFetcher {
    companion object {
        const val QUERY =
            """
            SELECT
                mid, out, data
            FROM messages_v2
            WHERE uid = ? AND date >= ? AND date < ?
            ORDER BY date DESC, mid DESC
            """
    }

    private fun parseMessage(
        id: Int,
        buffer: NativeByteBuffer,
        out: Boolean,
        selfId: Long
    ): TLRPC.Message {
        return try {
            TLRPC.Message
                .TLdeserialize(buffer, buffer.readInt32(false), false)
                .apply {
                    this.id = id
                    this.out = out
                }
                .also {
                    it.readAttachPath(buffer, selfId)
                }
        } finally {
            buffer.reuse()
        }
    }

    override suspend fun fetchActivity(
        accountId: Int,
        peerUserId: Long,
        timeZone: TimeZone,
        day: LocalDate,
        untilRestore: Boolean
    ): ChatHistoryFetcher.DayActivity {
        val selfId = UserConfig.getInstance(accountId).clientUserId

        val startLocalEpoch = day.toEpochSeconds(timeZone)
        val endLocalEpoch = day.next().toEpochSeconds(timeZone)

        val db = AccountInstance.getInstance(accountId).messagesStorage.database
        val cursor = db.queryFinalized(QUERY, peerUserId, startLocalEpoch, endLocalEpoch)

        var fromOwner = false
        var fromPeer = false
        var wasRestored = false
        var lastOwnerAt: Instant? = null
        var lastPeerAt: Instant? = null

        while (cursor.next()) {
            val message = parseMessage(
                cursor.intValue(0),
                if (cursor.isNull(2)) continue else cursor.byteBufferValue(2),
                cursor.intValue(1) > 0,
                selfId
            )

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
        val selfId = UserConfig.getInstance(accountId).clientUserId

        val startLocalEpoch = day.toEpochSeconds(timeZone)
        val endLocalEpoch = day.next().toEpochSeconds(timeZone)

        val db = AccountInstance.getInstance(accountId).messagesStorage.database
        val cursor = db.queryFinalized(QUERY, peerUserId, startLocalEpoch, endLocalEpoch)

        val messages = mutableListOf<TLRPC.Message>()
        var fromOwnerCount = 0
        var fromPeerCount = 0

        while (cursor.next()) {
            val message = parseMessage(
                cursor.intValue(0),
                if (cursor.isNull(2)) continue else cursor.byteBufferValue(2),
                cursor.intValue(1) > 0,
                selfId
            )

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
