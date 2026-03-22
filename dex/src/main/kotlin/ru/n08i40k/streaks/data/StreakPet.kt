package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.LocalDate

@Entity(
    tableName = "streak_pet",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class StreakPet(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "created_at") val createdAt: LocalDate,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: LocalDate,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "points") val points: Int,
)
