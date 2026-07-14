package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.datetime.LocalDate
import androidx.room.Ignore
import ru.n08i40k.streaks.Plugin

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
    @ColumnInfo(name = "fab_enabled") val fabEnabled: Boolean = true,

    ) {
    @delegate:Ignore
    val level: StreakPetLevel by lazy {
        Plugin.getInstance()
            .streakPetLevelRegistry
            .findByPointsApproximate(points)
    }
}
