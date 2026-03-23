package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.IGNORE
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.Update
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.data.StreakPetTaskType
import java.time.LocalDate

@Dao
interface StreakPetTaskDao {
    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId ORDER BY created_at DESC, type ASC")
    suspend fun findAllByRelation(ownerUserId: Long, peerUserId: Long): List<StreakPetTask>

    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND created_at = :createdAt ORDER BY type ASC")
    suspend fun findAllByRelationAndDay(
        ownerUserId: Long,
        peerUserId: Long,
        createdAt: LocalDate
    ): List<StreakPetTask>

    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND created_at = :createdAt AND is_completed = 0 ORDER BY type ASC")
    suspend fun findNotCompletedByRelationAndDay(
        ownerUserId: Long,
        peerUserId: Long,
        createdAt: LocalDate
    ): List<StreakPetTask>

    @Query(
        """
        SELECT * FROM streak_pet_task
        WHERE owner_user_id = :ownerUserId
          AND peer_user_id = :peerUserId
          AND created_at = :createdAt
          AND type = :type
        LIMIT 1
        """
    )
    suspend fun findByKey(
        ownerUserId: Long,
        peerUserId: Long,
        createdAt: LocalDate,
        type: StreakPetTaskType,
    ): StreakPetTask?

    @Insert(onConflict = IGNORE)
    suspend fun insertIfNotExistsAll(vararg records: StreakPetTask)

    @Insert(onConflict = REPLACE)
    suspend fun insertOrUpdateAll(vararg records: StreakPetTask)

    @Update
    suspend fun update(record: StreakPetTask)

    @Delete
    suspend fun delete(record: StreakPetTask)

    @Query("DELETE FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)
}
