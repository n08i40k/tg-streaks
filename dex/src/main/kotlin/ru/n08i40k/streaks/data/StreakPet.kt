package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlin.time.Instant
import androidx.room.Ignore
import kotlinx.datetime.TimeZone
import ru.n08i40k.streaks.Plugin

@Entity(
    tableName = "streak_pet",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class StreakPet(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Instant,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "points") val points: Int,
    @ColumnInfo(name = "fab_enabled") val fabEnabled: Boolean = true,

    @ColumnInfo(name = "raw_offset") val timeZone: TimeZone,
) {
    @delegate:Ignore
    val level: StreakPetLevel by lazy {
        Plugin.getInstance()
            .streakPetLevelRegistry
            .findByPointsApproximate(points)
    }
}
