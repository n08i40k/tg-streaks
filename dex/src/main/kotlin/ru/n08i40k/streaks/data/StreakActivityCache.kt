package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.time.LocalDate

@Entity(
    tableName = "streak_activity_cache",
    primaryKeys = ["account_id", "peer_user_id", "day"],
    indices = [Index(value = ["account_id", "peer_user_id", "day"])]
)
data class StreakActivityCache(
    @ColumnInfo(name = "account_id") val accountId: Int,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,
    @ColumnInfo(name = "day") val day: LocalDate,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "was_revived") val wasRevived: Boolean,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
