package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ru.n08i40k.streaks.data.Streak

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak")
    suspend fun getAll(): List<Streak>

    @Query("SELECT * FROM streak WHERE owner_user_id = :ownerUserId")
    suspend fun findAllByOwnerUserId(ownerUserId: Long): List<Streak>

    @Query("SELECT * FROM streak WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId LIMIT 1")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): Streak?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: Streak)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: Streak)

    @Query("DELETE FROM streak WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)

    @Update
    suspend fun update(streak: Streak)
}
