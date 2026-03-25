package ru.n08i40k.streaks.data

sealed interface StreakPetTaskPayload {
    val isCompleted: Boolean

    data class ExchangeOneMessage(
        val fromOwnerMessageId: Int?,
        val fromPeerMessageId: Int?,
    ) : StreakPetTaskPayload {
        override val isCompleted: Boolean
            get() = fromOwnerMessageId != null && fromPeerMessageId != null

        val remainingFromOwner get() = if (fromOwnerMessageId == null) 1 else 0
        val remainingFromPeer get() = if (fromPeerMessageId == null) 1 else 0

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
        override val isCompleted: Boolean
            get() = fromOwnerMessagesCount == 4 && fromPeerMessagesCount == 4

        val remainingFromOwner get() = 4 - fromOwnerMessagesCount
        val remainingFromPeer get() = 4 - fromPeerMessagesCount

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
        override val isCompleted: Boolean
            get() = fromOwnerMessagesCount == 10 && fromPeerMessagesCount == 10

        val remainingFromOwner get() = 10 - fromOwnerMessagesCount
        val remainingFromPeer get() = 10 - fromPeerMessagesCount

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
