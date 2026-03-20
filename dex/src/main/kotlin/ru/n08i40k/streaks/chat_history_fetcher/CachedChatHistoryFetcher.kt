package ru.n08i40k.streaks.chat_history_fetcher

import org.telegram.messenger.AccountInstance
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import java.time.LocalDate

class CachedChatHistoryFetcher : ChatHistoryFetcher {
    companion object {
        const val QUERY =
            "SELECT " +
                    "MAX(CASE WHEN out = 1 THEN 1 ELSE 0 END), " +
                    "MAX(CASE WHEN out = 0 THEN 1 ELSE 0 END) " +
                    "FROM messages_v2 " +
                    "WHERE uid = ? AND date >= ? AND date < ?"
    }

    override suspend fun fetch(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        untilRevive: Boolean
    ): ChatHistoryFetcher.Status {
        val startLocalEpoch = day.toEpochSecondSystem()
        val endLocalEpoch = day.next().toEpochSecondSystem()

        val db = AccountInstance.getInstance(accountId).messagesStorage.database
        val cursor = db.queryFinalized(QUERY, peerUserId, startLocalEpoch, endLocalEpoch)

        if (!cursor.next())
            return ChatHistoryFetcher.Status.NoActivity()

        val fromOwner =
            if (cursor.isNull(0))
                false
            else
                cursor.intValue(0) > 0

        val fromPeer =
            if (cursor.isNull(1))
                false
            else
                cursor.intValue(1) > 0

        return when {
            fromOwner && fromPeer -> ChatHistoryFetcher.Status.FromBoth(false)
            fromOwner -> ChatHistoryFetcher.Status.FromOwner(false)
            fromPeer -> ChatHistoryFetcher.Status.FromPeer(false)
            else -> ChatHistoryFetcher.Status.NoActivity()
        }
    }
}