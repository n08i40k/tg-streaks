package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_streak_popup",
    indices = [
        Index(value = ["account_id", "peer_user_id", "id"]),
        Index(value = ["dedupe_key"], unique = true),
    ]
)
data class ScheduledStreakPopup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Int,

    @ColumnInfo(name = "peer_user_id")
    val peerUserId: Long,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "peer_name")
    val peerName: String,

    @ColumnInfo(name = "days")
    val days: Int,

    @ColumnInfo(name = "accent_color")
    val accentColor: Int,

    @ColumnInfo(name = "emoji_document_id")
    val emojiDocumentId: Long,

    @ColumnInfo(name = "popup_resource_name")
    val popupResourceName: String,

    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long,
)
