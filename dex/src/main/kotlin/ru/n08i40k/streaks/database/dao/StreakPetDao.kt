package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ru.n08i40k.streaks.data.StreakPet

@Dao
interface StreakPetDao {
    @Query("SELECT * FROM streak_pet WHERE owner_user_id = :ownerUserId")
    suspend fun findAllByOwnerUserId(ownerUserId: Long): List<StreakPet>

    @Query("SELECT * FROM streak_pet WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId LIMIT 1")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): StreakPet?

    @Insert
    suspend fun insertAll(vararg records: StreakPet)

    @Update
    suspend fun update(record: StreakPet)

    @Delete
    suspend fun delete(record: StreakPet)

    @Query("DELETE FROM streak_pet WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)
}
