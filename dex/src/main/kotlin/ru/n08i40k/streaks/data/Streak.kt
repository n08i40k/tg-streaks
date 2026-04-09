package ru.n08i40k.streaks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import ru.n08i40k.streaks.Plugin
import java.time.LocalDate
import kotlin.math.min

@Entity(
    tableName = "streak",
    primaryKeys = ["owner_user_id", "peer_user_id"]
)
data class Streak(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long,
    @ColumnInfo(name = "peer_user_id") val peerUserId: Long,

    @ColumnInfo(name = "created_at") val createdAt: LocalDate,

    @ColumnInfo(name = "update_from_owner_at") val updateFromOwnerAt: LocalDate,
    @ColumnInfo(name = "update_from_peer_at") val updateFromPeerAt: LocalDate,

    @ColumnInfo(name = "revives_count") val revivesCount: Int,
    @ColumnInfo(name = "death_notified") val deathNotified: Boolean = false,
) {
    @Ignore
    val length: Int

    @Ignore
    val frozen: Boolean

    @Ignore
    val dead: Boolean

    @Ignore
    val canRevive: Boolean

    val level: StreakLevel
        get() {
            val registry = Plugin.getInstance().streakLevelRegistry

            return registry.findByLengthApproximate(length).let {
                if (this.frozen) {
                    val coldLevel = registry.findByLengthPrecise(0)
                        ?: throw NullPointerException("Cold streak level is not registered")

                    return@let coldLevel.copy(length = it.length)
                }

                return@let it
            }
        }

    init {
        val nowEpoch = LocalDate.now().toEpochDay()

        val fromOwnerEpoch = updateFromOwnerAt.toEpochDay()
        val fromPeerEpoch = updateFromPeerAt.toEpochDay()

        frozen = nowEpoch > fromOwnerEpoch || nowEpoch > fromPeerEpoch
        dead = frozen && ((nowEpoch - fromOwnerEpoch) > 1 || (nowEpoch - fromPeerEpoch) > 1)
        canRevive = dead && ((nowEpoch - fromOwnerEpoch) <= 2 || (nowEpoch - fromPeerEpoch) <= 2)

        var length = min(fromOwnerEpoch, fromPeerEpoch) - createdAt.toEpochDay() + 1

        length -= revivesCount

        this.length = length.coerceAtLeast(1).toInt()
    }
}
