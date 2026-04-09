package ru.n08i40k.streaks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.n08i40k.streaks.data.StreakActivityCache
import java.time.LocalDate

@Dao
interface StreakActivityCacheDao {
    @Query(
        """
        SELECT * FROM streak_activity_cache
        WHERE account_id = :accountId AND peer_user_id = :peerUserId
        ORDER BY day ASC
        """
    )
    suspend fun findByRelation(accountId: Int, peerUserId: Long): List<StreakActivityCache>

    @Query(
        """
        SELECT * FROM streak_activity_cache
        WHERE account_id = :accountId
            AND peer_user_id = :peerUserId
            AND day >= :fromDay
            AND day <= :toDay
        ORDER BY day ASC
        """
    )
    suspend fun findByRelationAndRange(
        accountId: Int,
        peerUserId: Long,
        fromDay: LocalDate,
        toDay: LocalDate,
    ): List<StreakActivityCache>

    @Query(
        """
        SELECT * FROM streak_activity_cache
        WHERE account_id = :accountId AND peer_user_id = :peerUserId AND day = :day
        LIMIT 1
        """
    )
    suspend fun findByRelationAndDay(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
    ): StreakActivityCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(vararg records: StreakActivityCache)

    @Query(
        """
        DELETE FROM streak_activity_cache
        WHERE account_id = :accountId AND peer_user_id = :peerUserId
        """
    )
    suspend fun deleteByRelation(accountId: Int, peerUserId: Long)

    @Query("DELETE FROM streak_activity_cache WHERE account_id = :accountId")
    suspend fun deleteByAccount(accountId: Int)
}
