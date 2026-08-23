package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.ServiceMessageCategories

@Dao
interface ServiceMessageCategoriesDao {
    @Query("SELECT * FROM service_message_categories WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId LIMIT 1")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): ServiceMessageCategories?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: ServiceMessageCategories)
}
