package ru.n08i40k.streaks.database

import android.content.Context
import android.os.Environment
import androidx.room.RoomDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.util.RuntimeGuard
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DatabaseBackupManager(
    private val db: RoomDatabase,
    private val logger: (String) -> Unit,
) {
    companion object {
        private const val PREFERENCES_NAME = "tg-streaks-db-backups"
        private const val LAST_AUTO_BACKUP_AT_KEY = "last_auto_backup_at"
        private const val DATABASE_NAME = "tg-streaks"
        private const val BACKUP_EXTENSION = "sqlite3"
        private const val MAX_BACKUPS = 30
        private val AUTO_BACKUP_INTERVAL = 24.hours
        private val BACKUP_NAME_FORMAT = LocalDateTime.Format {
            year(); monthNumber(); day(); char('-'); hour(); minute(); second()
        }
    }

    private val context: Context
        get() = ApplicationLoader.applicationContext

    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val lock = Any()

    fun exportNow(): File = synchronized(lock) {
        val backup = createBackup("manual")
        logger("Database backup exported: ${backup.absolutePath}")
        backup
    }

    suspend fun runAutoBackupLoop() {
        RuntimeGuard.awaitAppForeground("automatic database backup loop")
        ensureAutoBackupIfDue()

        while (currentCoroutineContext().isActive) {
            RuntimeGuard.pauseAwareDelay(
                millisUntilNextAutoBackup(),
                "automatic database backup loop"
            )
            RuntimeGuard.awaitAppForeground("automatic database backup loop")
            ensureAutoBackupIfDue()
        }
    }

    private fun ensureAutoBackupIfDue() {
        val now = Clock.System.now()
        val lastAutoBackupAt = lastAutoBackupAt()

        if (
            lastAutoBackupAt != null &&
            now - lastAutoBackupAt < AUTO_BACKUP_INTERVAL
        ) {
            return
        }

        synchronized(lock) {
            val refreshedLastAutoBackupAt = lastAutoBackupAt()
            val refreshedNow = Clock.System.now()

            if (
                refreshedLastAutoBackupAt != null &&
                refreshedNow - refreshedLastAutoBackupAt < AUTO_BACKUP_INTERVAL
            ) {
                return@synchronized
            }

            val backup = createBackup("auto")
            preferences.edit()
                .putLong(LAST_AUTO_BACKUP_AT_KEY, refreshedNow.epochSeconds)
                .apply()

            logger("Automatic database backup created: ${backup.absolutePath}")
        }
    }

    private fun lastAutoBackupAt(): Instant? {
        val epochSeconds = preferences.getLong(LAST_AUTO_BACKUP_AT_KEY, 0L)
        if (epochSeconds <= 0L) {
            return null
        }

        return Instant.fromEpochSeconds(epochSeconds)
    }

    private fun millisUntilNextAutoBackup(): Long {
        val lastAutoBackupAt = lastAutoBackupAt() ?: return AUTO_BACKUP_INTERVAL.inWholeMilliseconds
        val elapsed = Clock.System.now() - lastAutoBackupAt
        val remaining = AUTO_BACKUP_INTERVAL - elapsed
        return remaining.inWholeMilliseconds.coerceAtLeast(60_000L)
    }

    private fun createBackup(source: String): File {
        val backupsDir = backupsDir()
        val timestamp = Clock.System.now().toLocalDateTime(TimeZone.UTC).format(BACKUP_NAME_FORMAT)
        val backup = File(
            backupsDir,
            "tg-streaks-$timestamp-$source.$BACKUP_EXTENSION"
        )
        val target = context.getDatabasePath(DATABASE_NAME)
        val sqliteDb = db.openHelper.writableDatabase

        sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()

        target.copyTo(backup, overwrite = true)
        pruneOldBackups(backupsDir)
        return backup
    }

    @Suppress("DEPRECATION")
    private fun backupsDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "tg-streaks"
        ).apply {
            mkdirs()
        }

    private fun pruneOldBackups(backupsDir: File) {
        backupsDir.listFiles()
            ?.filter { it.isFile && it.extension == BACKUP_EXTENSION }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_BACKUPS)
            ?.forEach(File::delete)
    }
}
