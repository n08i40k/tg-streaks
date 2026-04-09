package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.StreakManualRevive
import java.time.LocalDate

@Dao
interface StreakManualReviveDao {
    @Query("SELECT * FROM streak_manual_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId ORDER BY revived_at ASC")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): List<StreakManualRevive>

    @Query("SELECT COUNT(*) FROM streak_manual_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun countByRelation(ownerUserId: Long, peerUserId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM streak_manual_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND revived_at = :day)")
    suspend fun exists(ownerUserId: Long, peerUserId: Long, day: LocalDate): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: StreakManualRevive)

    @Query("DELETE FROM streak_manual_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)
}
