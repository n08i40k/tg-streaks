package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.datetime.TimeZone

@Entity(
    tableName = "peer_time_zone",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class PeerTimeZone(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,
    @ColumnInfo(name = "raw_offset") val timeZone: TimeZone,
)
