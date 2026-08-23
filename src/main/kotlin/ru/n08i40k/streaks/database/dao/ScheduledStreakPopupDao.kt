package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.ScheduledStreakPopup

@Dao
interface ScheduledStreakPopupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ScheduledStreakPopup): Long

    @Query(
        """
        SELECT * FROM scheduled_streak_popup
        WHERE account_id = :accountId AND peer_user_id = :peerUserId
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findFirstByRelation(accountId: Int, peerUserId: Long): ScheduledStreakPopup?

    @Delete
    suspend fun delete(record: ScheduledStreakPopup)

    @Query(
        """
        DELETE FROM scheduled_streak_popup
        WHERE account_id = :accountId
            AND peer_user_id = :peerUserId
            AND id NOT IN (
                SELECT id FROM scheduled_streak_popup
                WHERE account_id = :accountId AND peer_user_id = :peerUserId
                ORDER BY id DESC
                LIMIT :keepCount
            )
        """
    )
    suspend fun trimRelationQueue(accountId: Int, peerUserId: Long, keepCount: Int)
}
