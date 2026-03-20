package ru.n08i40k.streaks.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
