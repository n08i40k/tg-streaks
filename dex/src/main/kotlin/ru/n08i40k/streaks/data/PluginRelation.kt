package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "plugin_relation",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class PluginRelation(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,
    @ColumnInfo(name = "has_plugin") val hasPlugin: Boolean,
)
