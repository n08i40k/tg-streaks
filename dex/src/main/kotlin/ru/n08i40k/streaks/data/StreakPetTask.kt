package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import java.time.LocalDate

@Entity(
    tableName = "streak_pet_task",
    primaryKeys = ["owner_user_id", "peer_user_id", "created_at", "type"],
    foreignKeys = [
        ForeignKey(
            entity = StreakPet::class,
            parentColumns = ["owner_user_id", "peer_user_id"],
            childColumns = ["owner_user_id", "peer_user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("owner_user_id", "peer_user_id", "created_at")]
)
data class StreakPetTask(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "created_at") val createdAt: LocalDate,
    @ColumnInfo(name = "type") val type: StreakPetTaskType,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean,
    @ColumnInfo(name = "payload") val dbPayload: StreakPetTaskPayload? = null,
) {
    @Ignore
    val payload: StreakPetTaskPayload = dbPayload ?: type.defaultPayload

    companion object {
        const val MAX_PER_SIDE = 1 + 4 + 10

        fun getNewTasksList(
            ownerUserId: Long,
            peerUserId: Long,
            day: LocalDate
        ): ArrayList<StreakPetTask> = arrayListOf(
            StreakPetTask(
                ownerUserId,
                peerUserId,
                day,
                StreakPetTaskType.EXCHANGE_ONE_MESSAGE,
                false,
                null
            ),
            StreakPetTask(
                ownerUserId,
                peerUserId,
                day,
                StreakPetTaskType.SEND_FOUR_MESSAGES_EACH,
                false,
                null
            ),
            StreakPetTask(
                ownerUserId,
                peerUserId,
                day,
                StreakPetTaskType.SEND_TEN_MESSAGES_EACH,
                false,
                null
            ),
        )
    }
}
