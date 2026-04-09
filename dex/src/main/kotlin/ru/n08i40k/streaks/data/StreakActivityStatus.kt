package ru.n08i40k.streaks.data

import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher

enum class StreakActivityStatus(val code: Int) {
    NO_ACTIVITY(0),
    OWNER(1),
    PEER(2),
    BOTH(3);

    fun mergeMessage(out: Boolean): StreakActivityStatus =
        when (this) {
            NO_ACTIVITY -> if (out) OWNER else PEER
            OWNER -> if (out) OWNER else BOTH
            PEER -> if (out) BOTH else PEER
            BOTH -> BOTH
        }

    companion object {
        fun fromCode(code: Int): StreakActivityStatus =
            entries.firstOrNull { it.code == code } ?: NO_ACTIVITY

        fun fromFetcherStatus(status: ChatHistoryFetcher.Status): StreakActivityStatus =
            when (status) {
                is ChatHistoryFetcher.Status.FromBoth -> BOTH
                is ChatHistoryFetcher.Status.FromOwner -> OWNER
                is ChatHistoryFetcher.Status.FromPeer -> PEER
                is ChatHistoryFetcher.Status.NoActivity -> NO_ACTIVITY
            }
    }
}
