package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.IGNORE
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.Update
import kotlinx.datetime.LocalDate
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.data.StreakPetTaskType

@Dao
interface StreakPetTaskDao {
    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId ORDER BY created_date DESC, type ASC")
    suspend fun findAllByRelation(ownerUserId: Long, peerUserId: Long): List<StreakPetTask>

    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND created_date = :createdDate ORDER BY type ASC")
    suspend fun findAllByRelationAndDay(
        ownerUserId: Long,
        peerUserId: Long,
        createdDate: LocalDate
    ): List<StreakPetTask>

    @Query("SELECT * FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId AND created_date = :createdDate AND is_completed = 0 ORDER BY type ASC")
    suspend fun findNotCompletedByRelationAndDay(
        ownerUserId: Long,
        peerUserId: Long,
        createdDate: LocalDate
    ): List<StreakPetTask>

    @Query(
        """
        SELECT * FROM streak_pet_task
        WHERE owner_user_id = :ownerUserId
          AND peer_user_id = :peerUserId
          AND created_date = :createdDate
          AND type = :type
        LIMIT 1
        """
    )
    suspend fun findByKey(
        ownerUserId: Long,
        peerUserId: Long,
        createdDate: LocalDate,
        type: StreakPetTaskType,
    ): StreakPetTask?

    @Insert(onConflict = IGNORE)
    suspend fun insertIfNotExistsAll(records: Collection<StreakPetTask>)

    @Insert(onConflict = REPLACE)
    suspend fun insertOrReplaceAll(records: Collection<StreakPetTask>)

    @Update
    suspend fun update(record: StreakPetTask)

    @Delete
    suspend fun delete(record: StreakPetTask)

    @Query("DELETE FROM streak_pet_task WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)
}
