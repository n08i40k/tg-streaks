package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "service_message_categories",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class ServiceMessageCategories(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,
    @ColumnInfo(name = "lifecycle") val lifecycle: Boolean,
    @ColumnInfo(name = "level_up") val levelUp: Boolean,
    @ColumnInfo(name = "pet") val pet: Boolean,
    @ColumnInfo(name = "sync") val sync: Boolean,
)
