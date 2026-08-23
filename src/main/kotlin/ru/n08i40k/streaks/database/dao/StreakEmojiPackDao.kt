package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ru.n08i40k.streaks.data.StreakEmojiPack
import java.util.UUID

@Dao
interface StreakEmojiPackDao {
    @Query("SELECT * FROM streak_emoji_packs")
    suspend fun getAll(): List<StreakEmojiPack>

    @Query("SELECT * FROM streak_emoji_packs WHERE id = :id LIMIT 1")
    suspend fun findById(id: UUID): StreakEmojiPack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(pack: StreakEmojiPack)

    @Update
    suspend fun update(pack: StreakEmojiPack)

    @Update
    suspend fun updateAll(packs: Collection<StreakEmojiPack>)

    @Delete
    suspend fun delete(pack: StreakEmojiPack)
}
