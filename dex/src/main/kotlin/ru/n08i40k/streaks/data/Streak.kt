package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import kotlinx.datetime.TimeZone
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.extension.toEpochDays
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    tableName = "streak",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class Streak(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "created_at") val createdAt: Instant,

    @ColumnInfo(name = "update_from_owner_at") val updateFromOwnerAt: Instant,
    @ColumnInfo(name = "update_from_peer_at") val updateFromPeerAt: Instant,

    @ColumnInfo(name = "revives_count") val revivesCount: Int,
    @ColumnInfo(name = "death_notified") val deathNotified: Boolean = false,
    @ColumnInfo(name = "warning_notified") val warningNotified: Boolean = false,

    @ColumnInfo(name = "raw_offset") val timeZone: TimeZone,
) {
    companion object {
        const val MIN_VISIBLE_LENGTH = 3
    }

    @Ignore
    val length: Int

    @Ignore
    val frozen: Boolean

    @Ignore
    val dead: Boolean

    @Ignore
    val canRevive: Boolean

    @delegate:Ignore
    val level: StreakLevel by lazy {
        val registry = Plugin.getInstance().streakLevelRegistry

        registry.findByLengthApproximate(length).let {
            if (this.frozen) {
                val coldLevel = registry.findByLengthPrecise(0)
                    ?: throw NullPointerException("Cold streak level is not registered")

                return@let coldLevel.copy(length = it.length)
            }

            return@let it
        }
    }

    @get:Ignore
    val isVisible get() = length >= MIN_VISIBLE_LENGTH

    init {
        val nowEpoch = Clock.System.now().toEpochDays(timeZone)

        val fromOwnerEpoch = updateFromOwnerAt.toEpochDays(timeZone)
        val fromPeerEpoch = updateFromPeerAt.toEpochDays(timeZone)

        frozen = nowEpoch > fromOwnerEpoch || nowEpoch > fromPeerEpoch
        dead = frozen && ((nowEpoch - fromOwnerEpoch) > 1 || (nowEpoch - fromPeerEpoch) > 1)
        canRevive = dead && ((nowEpoch - fromOwnerEpoch) <= 2 || (nowEpoch - fromPeerEpoch) <= 2)

        var length = min(fromOwnerEpoch, fromPeerEpoch) - createdAt.toEpochDays(timeZone) + 1

        length -= revivesCount

        this.length = length.coerceAtLeast(1).toInt()
    }
}
