package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.StreakRevive
import java.time.LocalDate

@Dao
interface StreakReviveDao {
    @Query("SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId")
    suspend fun findAllByOwnerUserId(ownerUserId: Long): List<StreakRevive>

    @Query("SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): List<StreakRevive>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(vararg records: StreakRevive)

    @Query("SELECT EXISTS(SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND revived_at = :day)")
    suspend fun isRevived(ownerUserId: Long, peerUserId: Long, day: LocalDate): Boolean
}