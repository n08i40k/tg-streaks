package ru.n08i40k.streaks.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.n08i40k.streaks.database.converter.LocalDateConverter
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.database.dao.ScheduledStreakPopupDao
import ru.n08i40k.streaks.database.dao.StreakDao
import ru.n08i40k.streaks.data.StreakRevive
import ru.n08i40k.streaks.database.dao.StreakReviveDao

@Database(
    entities = [
        Streak::class,
        StreakRevive::class,
        ScheduledStreakPopup::class,
    ],
    version = 3
)
@TypeConverters(LocalDateConverter::class)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun streakDao(): StreakDao
    abstract fun streakReviveDao(): StreakReviveDao
    abstract fun scheduledStreakPopupDao(): ScheduledStreakPopupDao
}
