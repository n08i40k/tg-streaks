package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ru.n08i40k.streaks.data.StreakLevelPack
import java.util.UUID

@Dao
interface StreakLevelPackDao {
    @Query("SELECT * FROM streak_emoji_packs")
    suspend fun getAll(): List<StreakLevelPack>

    @Query("SELECT * FROM streak_emoji_packs WHERE id = :id LIMIT 1")
    suspend fun findById(id: UUID): StreakLevelPack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(pack: StreakLevelPack)

    @Update
    suspend fun update(pack: StreakLevelPack)

    @Update
    suspend fun updateAll(packs: Collection<StreakLevelPack>)

    @Delete
    suspend fun delete(pack: StreakLevelPack)
}
