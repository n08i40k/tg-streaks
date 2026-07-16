package ru.n08i40k.streaks.extension

import androidx.room.Room
import androidx.room.RoomDatabase
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.database.MIGRATION_10_11
import ru.n08i40k.streaks.database.MIGRATION_1_2
import ru.n08i40k.streaks.database.MIGRATION_2_3
import ru.n08i40k.streaks.database.MIGRATION_3_5
import ru.n08i40k.streaks.database.MIGRATION_5_6
import ru.n08i40k.streaks.database.MIGRATION_6_7
import ru.n08i40k.streaks.database.MIGRATION_7_8
import ru.n08i40k.streaks.database.MIGRATION_8_9
import ru.n08i40k.streaks.database.MIGRATION_9_10
import ru.n08i40k.streaks.database.PluginDatabase

fun Room.buildPluginDatabase(path: String = "tg-streaks"): PluginDatabase =
    databaseBuilder(
        ApplicationLoader.applicationContext,
        PluginDatabase::class.java,
        path
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_5, MIGRATION_5_6)
        .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
        .addMigrations(MIGRATION_10_11)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()