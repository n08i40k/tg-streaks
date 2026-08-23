package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.PluginRelation

@Dao
interface PluginRelationDao {
    @Query("SELECT * FROM plugin_relation WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId LIMIT 1")
    suspend fun findByRelation(ownerUserId: Long, peerUserId: Long): PluginRelation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: PluginRelation)

    @Query("DELETE FROM plugin_relation WHERE owner_user_id = :ownerUserId AND peer_user_id = :peerUserId")
    suspend fun deleteByRelation(ownerUserId: Long, peerUserId: Long)
}
