package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.time.LocalDate

@Entity(
    tableName = "streak_manual_revive",
    primaryKeys = ["owner_user_id", "peer_user_id", "revived_at"],
    indices = [Index(value = ["owner_user_id", "peer_user_id"])]
)
data class StreakManualRevive(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,
    @ColumnInfo(name = "revived_at") val revivedAt: LocalDate,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)
