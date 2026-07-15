package ru.n08i40k.streaks.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.n08i40k.streaks.database.converter.InstantConverter
import ru.n08i40k.streaks.database.converter.LocalDateConverter
import ru.n08i40k.streaks.database.converter.StreakPetTaskPayloadConverter
import ru.n08i40k.streaks.database.converter.StreakPetTaskTypeConverter
import ru.n08i40k.streaks.database.converter.TimeZoneConverter
import ru.n08i40k.streaks.data.PeerTimeZone
import ru.n08i40k.streaks.data.PluginRelation
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.ServiceMessageCategories
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakPet
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.database.dao.PeerTimeZoneDao
import ru.n08i40k.streaks.database.dao.PluginRelationDao
import ru.n08i40k.streaks.database.dao.ScheduledStreakPopupDao
import ru.n08i40k.streaks.database.dao.ServiceMessageCategoriesDao
import ru.n08i40k.streaks.database.dao.StreakDao
import ru.n08i40k.streaks.database.dao.StreakPetDao
import ru.n08i40k.streaks.database.dao.StreakPetTaskDao
import ru.n08i40k.streaks.data.StreakRestore
import ru.n08i40k.streaks.database.dao.StreakRestoreDao

@Database(
    entities = [
        Streak::class,
        StreakRestore::class,
        ScheduledStreakPopup::class,
        StreakPet::class,
        StreakPetTask::class,
        PeerTimeZone::class,
        PluginRelation::class,
        ServiceMessageCategories::class,
    ],
    version = 11
)
@TypeConverters(
    InstantConverter::class,
    LocalDateConverter::class,
    StreakPetTaskTypeConverter::class,
    StreakPetTaskPayloadConverter::class,
    TimeZoneConverter::class,
)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun streakDao(): StreakDao
    abstract fun streakRestoreDao(): StreakRestoreDao
    abstract fun scheduledStreakPopupDao(): ScheduledStreakPopupDao
    abstract fun streakPetDao(): StreakPetDao
    abstract fun streakPetTaskDao(): StreakPetTaskDao
    abstract fun peerTimeZoneDao(): PeerTimeZoneDao
    abstract fun pluginRelationDao(): PluginRelationDao
    abstract fun serviceMessageCategoriesDao(): ServiceMessageCategoriesDao
}
