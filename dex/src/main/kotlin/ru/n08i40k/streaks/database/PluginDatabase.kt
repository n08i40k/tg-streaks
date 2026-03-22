package ru.n08i40k.streaks.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.n08i40k.streaks.database.converter.LocalDateConverter
import ru.n08i40k.streaks.database.converter.StreakPetTaskPayloadConverter
import ru.n08i40k.streaks.database.converter.StreakPetTaskTypeConverter
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakPet
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.database.dao.ScheduledStreakPopupDao
import ru.n08i40k.streaks.database.dao.StreakDao
import ru.n08i40k.streaks.database.dao.StreakPetDao
import ru.n08i40k.streaks.database.dao.StreakPetTaskDao
import ru.n08i40k.streaks.data.StreakRevive
import ru.n08i40k.streaks.database.dao.StreakReviveDao

@Database(
    entities = [
        Streak::class,
        StreakRevive::class,
        ScheduledStreakPopup::class,
        StreakPet::class,
        StreakPetTask::class,
    ],
    version = 5
)
@TypeConverters(
    LocalDateConverter::class,
    StreakPetTaskTypeConverter::class,
    StreakPetTaskPayloadConverter::class,
)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun streakDao(): StreakDao
    abstract fun streakReviveDao(): StreakReviveDao
    abstract fun scheduledStreakPopupDao(): ScheduledStreakPopupDao
    abstract fun streakPetDao(): StreakPetDao
    abstract fun streakPetTaskDao(): StreakPetTaskDao
}
