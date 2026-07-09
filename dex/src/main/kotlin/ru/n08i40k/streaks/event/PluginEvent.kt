package ru.n08i40k.streaks.event

import ru.n08i40k.streaks.data.Streak
import kotlin.time.Instant
import ru.n08i40k.streaks.data.StreakPet

sealed interface PluginEvent {
    sealed interface TimestampEvent {
        val timestamp: Instant
    }

    sealed interface RecordEvent<T> {
        val record: T
    }

    sealed interface TransitionEvent<T> : RecordEvent<T> {
        val sourceRecord: T
        val targetRecord: T

        override val record: T
            get() = targetRecord
    }

    sealed interface RebuiltEvent<T> : RecordEvent<T> {
        val sourceRecord: T?
        val targetRecord: T

        override val record: T
            get() = targetRecord
    }

    sealed interface AccountEvent : PluginEvent {
        val accountId: Int
    }

    sealed interface PeerEvent : AccountEvent {
        val peerUserId: Long
    }

    sealed interface StreakEvent : PeerEvent, TimestampEvent, RecordEvent<Streak> {
        override val peerUserId: Long
            get() = record.peerUserId
    }

    data class StreakCreatedEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: Streak,
    ) : StreakEvent

    data class StreakLostEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: Streak,
    ) : StreakEvent

    data class StreakRestoredEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: Streak,
        val byPeer: Boolean,
    ) : StreakEvent

    data class StreakDeletedEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: Streak,
    ) : StreakEvent

    data class StreakGrowUpEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val sourceRecord: Streak,
        override val targetRecord: Streak,
    ) : StreakEvent, TransitionEvent<Streak>

    data class StreakRebuiltEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val sourceRecord: Streak?,
        override val targetRecord: Streak,
    ) : StreakEvent, RebuiltEvent<Streak>

    data class StreakDeathWarningEvent(
        override val accountId: Int,
        override val peerUserId: Long,
        override val timestamp: Instant,
        val streak: Streak,
        val peerName: String,
        val active: Boolean,
        val timeUntilDeathSeconds: Long,
    ) : PeerEvent, TimestampEvent


    sealed interface StreakPetEvent : PeerEvent, TimestampEvent, RecordEvent<StreakPet> {
        override val peerUserId: Long
            get() = record.peerUserId
    }

    data class StreakPetCreatedEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: StreakPet,
        val byInvite: Boolean
    ) : StreakPetEvent

    data class StreakPetRenamedEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: StreakPet,
        val by: By,
    ) : StreakPetEvent {
        enum class By {
            SELF,
            SELF_MESSAGE,
            PEER_MESSAGE
        }
    }

    data class StreakPetDeletedEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val record: StreakPet
    ) : StreakPetEvent

    data class StreakPetRebuiltEvent(
        override val accountId: Int,
        override val timestamp: Instant,
        override val sourceRecord: StreakPet?,
        override val targetRecord: StreakPet,
    ) : StreakPetEvent, RebuiltEvent<StreakPet>
}
