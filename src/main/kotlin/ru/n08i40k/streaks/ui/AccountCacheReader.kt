package ru.n08i40k.streaks.ui

import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object AccountCacheReader {
    private const val HISTORY_QUERY =
        """
        SELECT
            mid, out, data
        FROM messages_v2
        WHERE uid = ? AND date >= ? AND date < ?
        ORDER BY date DESC, mid DESC
        """

    private const val LAST_ID_QUERY =
        """
        SELECT
            did, last_mid
        FROM dialogs
        """

    class MessageHistoryIterator(
        private val accountUserId: Long,
        private val cursor: SQLiteCursor
    ) : Iterator<TLRPC.Message> {
        private var hasNext = cursor.next()

        override fun next(): TLRPC.Message {
            if (!hasNext())
                throw NoSuchElementException()

            val id = cursor.intValue(0)
            val out = cursor.intValue(1) > 0
            val buffer = cursor.byteBufferValue(2)

            val message = try {
                TLRPC.Message
                    .TLdeserialize(buffer, buffer.readInt32(false), false)
                    .apply {
                        this.id = id
                        this.out = out
                    }
                    .also {
                        it.readAttachPath(buffer, accountUserId)
                    }
            } finally {
                buffer.reuse()
            }

            hasNext = cursor.next()

            return message
        }

        override fun hasNext(): Boolean = hasNext
    }

    fun getHistoryCursor(
        accountId: Int,
        fromLocalEpoch: Long,
        toLocalEpoch: Long,
        userId: Long
    ): MessageHistoryIterator {
        val accountUserId = UserConfig.getInstance(accountId).clientUserId

        val cursor = MessagesStorage.getInstance(accountId)
            .database
            .queryFinalized(HISTORY_QUERY, userId, fromLocalEpoch, toLocalEpoch)

        return MessageHistoryIterator(accountUserId, cursor)
    }

    fun getLastMessageId(accountId: Int, dialogIds: Collection<Long>): Map<Long, Int> {
        val cursor = MessagesStorage.getInstance(accountId)
            .database
            .queryFinalized(LAST_ID_QUERY)

        val result = hashMapOf<Long, Int>()

        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val lastMessageId = cursor.intValue(1)

                if (!dialogIds.contains(dialogId))
                    continue

                result[dialogId] = lastMessageId
            }
        } finally {
            cursor.dispose()
        }

        return result
    }
}
