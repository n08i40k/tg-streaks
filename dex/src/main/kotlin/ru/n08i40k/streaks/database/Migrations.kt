package ru.n08i40k.streaks.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import ru.n08i40k.streaks.extension.rawOffset

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_streak_popup` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `account_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `peer_name` TEXT NOT NULL,
                `days` INTEGER NOT NULL,
                `accent_color` INTEGER NOT NULL,
                `emoji_document_id` INTEGER NOT NULL,
                `dedupe_key` TEXT NOT NULL,
                `scheduled_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_scheduled_streak_popup_account_id_peer_user_id_id`
            ON `scheduled_streak_popup` (`account_id`, `peer_user_id`, `id`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_streak_popup_dedupe_key`
            ON `scheduled_streak_popup` (`dedupe_key`)
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `scheduled_streak_popup`
            ADD COLUMN `popup_resource_name` TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
    }
}

val MIGRATION_3_5 = object : Migration(3, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_pet` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `last_checked_at` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `points` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_pet_task` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `is_completed` INTEGER NOT NULL,
                `payload` TEXT,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`, `created_at`, `type`),
                FOREIGN KEY(`owner_user_id`, `peer_user_id`) REFERENCES `streak_pet`(`owner_user_id`, `peer_user_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_streak_pet_task_owner_user_id_peer_user_id_created_at`
            ON `streak_pet_task` (`owner_user_id`, `peer_user_id`, `created_at`)
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `streak`
            ADD COLUMN `death_notified` INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_activity_cache` (
                `account_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `day` INTEGER NOT NULL,
                `status` INTEGER NOT NULL,
                `was_revived` INTEGER NOT NULL,
                `updated_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`account_id`, `peer_user_id`, `day`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_streak_activity_cache_account_id_peer_user_id_day`
            ON `streak_activity_cache` (`account_id`, `peer_user_id`, `day`)
            """.trimIndent()
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_manual_revive` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `revived_at` INTEGER NOT NULL,
                `created_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`, `revived_at`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_streak_manual_revive_owner_user_id_peer_user_id`
            ON `streak_manual_revive` (`owner_user_id`, `peer_user_id`)
            """.trimIndent()
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `streak`
            ADD COLUMN `warning_notified` INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `streak_pet`
            ADD COLUMN `fab_enabled` INTEGER NOT NULL DEFAULT 1
            """.trimIndent()
        )
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    private fun epochDaysToMillis(epochDays: Long): Long {
        val zone = TimeZone.currentSystemDefault()
        return LocalDate.fromEpochDays(epochDays).atStartOfDayIn(zone).toEpochMilliseconds()
    }

    private fun convertDaysToMillis(
        db: SupportSQLiteDatabase,
        table: String,
        cols: List<String>,
    ) {
        val cursor = db.query("SELECT rowid, ${cols.joinToString(", ")} FROM `$table`")
        cursor.use {
            while (it.moveToNext()) {
                val rowId = it.getLong(0)
                val newValues = List(cols.size) { index -> epochDaysToMillis(it.getLong(index + 1)) }

                val setClause = cols.joinToString(", ") { col -> "`$col` = ?" }
                db.execSQL(
                    "UPDATE `$table` SET $setClause WHERE rowid = ?",
                    (newValues + rowId).toTypedArray()
                )
            }
        }
    }

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `peer_time_zone` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `raw_offset` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `plugin_relation` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `has_plugin` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `service_message_categories` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `lifecycle` INTEGER NOT NULL,
                `level_up` INTEGER NOT NULL,
                `pet` INTEGER NOT NULL,
                `sync` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`)
            )
            """.trimIndent()
        )

        val rawOffset = TimeZone.currentSystemDefault().rawOffset
        db.execSQL("ALTER TABLE `streak` ADD COLUMN `raw_offset` INTEGER NOT NULL DEFAULT $rawOffset")
        db.execSQL("ALTER TABLE `streak_pet` ADD COLUMN `raw_offset` INTEGER NOT NULL DEFAULT $rawOffset")

        convertDaysToMillis(
            db,
            "streak",
            listOf("created_at", "update_from_owner_at", "update_from_peer_at"),
        )
        convertDaysToMillis(db, "streak_pet", listOf("created_at", "last_checked_at"))

        db.execSQL("DROP TABLE IF EXISTS `streak_activity_cache`")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_revive_new` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `revive_date` INTEGER NOT NULL,
                `revived_at` INTEGER NOT NULL,
                `manual` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`, `revive_date`),
                FOREIGN KEY(`owner_user_id`, `peer_user_id`) REFERENCES `streak`(`owner_user_id`, `peer_user_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        fun copyRestores(table: String, manual: Int) {
            val cursor = db.query("SELECT owner_user_id, peer_user_id, revived_at FROM `$table`")
            cursor.use {
                while (it.moveToNext()) {
                    val ownerUserId = it.getLong(0)
                    val peerUserId = it.getLong(1)
                    val restoreDate = it.getLong(2)

                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO `streak_revive_new`
                            (`owner_user_id`, `peer_user_id`, `revive_date`, `revived_at`, `manual`)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            ownerUserId,
                            peerUserId,
                            restoreDate,
                            epochDaysToMillis(restoreDate),
                            manual,
                        )
                    )
                }
            }
        }

        copyRestores("streak_revive", 0)
        copyRestores("streak_manual_revive", 1)

        db.execSQL("DROP TABLE `streak_revive`")
        db.execSQL("DROP TABLE IF EXISTS `streak_manual_revive`")
        db.execSQL("ALTER TABLE `streak_revive_new` RENAME TO `streak_revive`")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_streak_revive_owner_user_id_peer_user_id`
            ON `streak_revive` (`owner_user_id`, `peer_user_id`)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streak_pet_task_new` (
                `owner_user_id` INTEGER NOT NULL,
                `peer_user_id` INTEGER NOT NULL,
                `created_date` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `is_completed` INTEGER NOT NULL,
                `payload` TEXT,
                PRIMARY KEY(`owner_user_id`, `peer_user_id`, `created_date`, `type`),
                FOREIGN KEY(`owner_user_id`, `peer_user_id`) REFERENCES `streak_pet`(`owner_user_id`, `peer_user_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        run {
            val cursor = db.query(
                "SELECT owner_user_id, peer_user_id, created_at, type, is_completed, payload FROM `streak_pet_task`"
            )
            cursor.use {
                while (it.moveToNext()) {
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO `streak_pet_task_new`
                            (`owner_user_id`, `peer_user_id`, `created_date`, `type`, `is_completed`, `payload`)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            it.getLong(0),
                            it.getLong(1),
                            it.getLong(2),
                            it.getString(3),
                            it.getLong(4),
                            if (it.isNull(5)) null else it.getString(5),
                        )
                    )
                }
            }
        }

        db.execSQL("DROP TABLE `streak_pet_task`")
        db.execSQL("ALTER TABLE `streak_pet_task_new` RENAME TO `streak_pet_task`")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_streak_pet_task_owner_user_id_peer_user_id_created_date`
            ON `streak_pet_task` (`owner_user_id`, `peer_user_id`, `created_date`)
            """.trimIndent()
        )
    }
}
