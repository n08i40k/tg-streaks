package ru.n08i40k.streaks.chat_history_fetcher

import org.telegram.messenger.AccountInstance
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import java.time.LocalDate

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
        day: LocalDate,
        untilRevive: Boolean
    ): ChatHistoryFetcher.Status {
        val selfId = UserConfig.getInstance(accountId).clientUserId

        val startLocalEpoch = day.toEpochSecondSystem()
        val endLocalEpoch = day.next().toEpochSecondSystem()

        val db = AccountInstance.getInstance(accountId).messagesStorage.database
        val cursor = db.queryFinalized(QUERY, peerUserId, startLocalEpoch, endLocalEpoch)

        var fromOwner = false
        var fromPeer = false
        var wasRevived = false

        while (cursor.next()) {
            val message = parseMessage(
                cursor.intValue(0),
                if (cursor.isNull(2)) continue else cursor.byteBufferValue(2),
                cursor.intValue(1) > 0,
                selfId
            )

            if (message.message == ServiceMessage.RESTORE_TEXT) {
                wasRevived = true

                @Suppress("KotlinConstantConditions")
                if (fromOwner && fromPeer && untilRevive)
                    break

                continue
            }

            if (ServiceMessage.isServiceText(message.message))
                continue

            if (message.out)
                fromOwner = true
            else
                fromPeer = true

            if (fromOwner && fromPeer && (!untilRevive || wasRevived))
                break
        }

        return when {
            fromOwner && fromPeer -> ChatHistoryFetcher.Status.FromBoth(wasRevived)
            fromOwner -> ChatHistoryFetcher.Status.FromOwner(wasRevived)
            fromPeer -> ChatHistoryFetcher.Status.FromPeer(wasRevived)
            else -> ChatHistoryFetcher.Status.NoActivity(wasRevived)
        }
    }

    override suspend fun fetchRawMessages(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        fromOwnerMax: Int,
        fromPeerMax: Int,
    ): List<TLRPC.Message> {
        val selfId = UserConfig.getInstance(accountId).clientUserId

        val startLocalEpoch = day.toEpochSecondSystem()
        val endLocalEpoch = day.next().toEpochSecondSystem()

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
