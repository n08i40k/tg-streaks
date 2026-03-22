package ru.n08i40k.streaks.data

sealed interface StreakPetTaskPayload {
    data class ExchangeOneMessage(
        val fromOwnerMessageId: Int?,
        val fromPeerMessageId: Int?,
    ) : StreakPetTaskPayload {
        companion object {
            val empty
                get() = ExchangeOneMessage(
                    fromOwnerMessageId = null,
                    fromPeerMessageId = null,
                )
        }
    }

    data class SendFourMessagesEach(
        val fromOwnerMessagesCount: Int,
        val fromOwnerLastMessageId: Int?,
        val fromPeerMessagesCount: Int,
        val fromPeerLastMessageId: Int?,
    ) : StreakPetTaskPayload {
        companion object {
            val empty
                get() = SendFourMessagesEach(
                    fromOwnerMessagesCount = 0,
                    fromOwnerLastMessageId = null,
                    fromPeerMessagesCount = 0,
                    fromPeerLastMessageId = null,
                )
        }
    }

    data class SendTenMessagesEach(
        val fromOwnerMessagesCount: Int,
        val fromOwnerLastMessageId: Int?,
        val fromPeerMessagesCount: Int,
        val fromPeerLastMessageId: Int?,
    ) : StreakPetTaskPayload {
        companion object {
            val empty
                get() = SendTenMessagesEach(
                    fromOwnerMessagesCount = 0,
                    fromOwnerLastMessageId = null,
                    fromPeerMessagesCount = 0,
                    fromPeerLastMessageId = null,
                )
        }
    }
}
