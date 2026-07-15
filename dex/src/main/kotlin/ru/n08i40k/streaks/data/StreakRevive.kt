package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

@Entity(
    tableName = "streak_revive",
    primaryKeys = ["owner_user_id", "peer_user_id", "revive_date"],
    foreignKeys = [
        ForeignKey(
            entity = Streak::class,
            parentColumns = ["owner_user_id", "peer_user_id"],
            childColumns = ["owner_user_id", "peer_user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("owner_user_id", "peer_user_id")]
)
data class StreakRevive(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "revive_date") val reviveDate: LocalDate,
    @ColumnInfo(name = "revived_at") val revivedAt: Instant,
    @ColumnInfo(name = "manual") val manual: Boolean = false,
)
