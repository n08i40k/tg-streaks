package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.datetime.LocalDate
import ru.n08i40k.streaks.data.StreakRestore

@Dao
interface StreakRestoreDao {
    @Query("SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId")
    suspend fun findAllByOwnerUserId(ownerUserId: Long): List<StreakRestore>

    @Query("SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): List<StreakRestore>

    @Query("SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND manual = 1")
    suspend fun findManualByRelation(ownerUserId: Long, peerUserId: Long): List<StreakRestore>

    @Query("SELECT COUNT(*) FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND manual = 1")
    suspend fun countManualByRelation(ownerUserId: Long, peerUserId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: StreakRestore)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(records: Collection<StreakRestore>)

    @Query("DELETE FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND manual = 0")
    suspend fun deleteAutoByRelation(ownerUserId: Long, peerUserId: Long)

    @Query("SELECT EXISTS(SELECT * FROM streak_revive WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND revive_date = :day)")
    suspend fun isRestored(ownerUserId: Long, peerUserId: Long, day: LocalDate): Boolean
}
