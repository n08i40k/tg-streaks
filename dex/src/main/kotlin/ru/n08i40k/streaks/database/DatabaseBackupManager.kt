package ru.n08i40k.streaks.database

import android.content.Context
import android.os.Environment
import androidx.room.RoomDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.telegram.messenger.ApplicationLoader
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
        private val AUTO_BACKUP_INTERVAL: Duration = Duration.ofHours(24)
        private val BACKUP_NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
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
        ensureAutoBackupIfDue()

        while (currentCoroutineContext().isActive) {
            delay(millisUntilNextAutoBackup())
            ensureAutoBackupIfDue()
        }
    }

    private fun ensureAutoBackupIfDue() {
        val now = Instant.now()
        val lastAutoBackupAt = lastAutoBackupAt()

        if (
            lastAutoBackupAt != null &&
            Duration.between(lastAutoBackupAt, now) < AUTO_BACKUP_INTERVAL
        ) {
            return
        }

        synchronized(lock) {
            val refreshedLastAutoBackupAt = lastAutoBackupAt()
            val refreshedNow = Instant.now()

            if (
                refreshedLastAutoBackupAt != null &&
                Duration.between(refreshedLastAutoBackupAt, refreshedNow) < AUTO_BACKUP_INTERVAL
            ) {
                return@synchronized
            }

            val backup = createBackup("auto")
            preferences.edit()
                .putLong(LAST_AUTO_BACKUP_AT_KEY, refreshedNow.epochSecond)
                .apply()

            logger("Automatic database backup created: ${backup.absolutePath}")
        }
    }

    private fun lastAutoBackupAt(): Instant? {
        val epochSeconds = preferences.getLong(LAST_AUTO_BACKUP_AT_KEY, 0L)
        if (epochSeconds <= 0L) {
            return null
        }

        return Instant.ofEpochSecond(epochSeconds)
    }

    private fun millisUntilNextAutoBackup(): Long {
        val lastAutoBackupAt = lastAutoBackupAt() ?: return AUTO_BACKUP_INTERVAL.toMillis()
        val elapsed = Duration.between(lastAutoBackupAt, Instant.now())
        val remaining = AUTO_BACKUP_INTERVAL.minus(elapsed)
        return remaining.toMillis().coerceAtLeast(60_000L)
    }

    private fun createBackup(source: String): File {
        val backupsDir = backupsDir()
        val backup = File(
            backupsDir,
            "tg-streaks-${BACKUP_NAME_FORMATTER.format(Instant.now())}-$source.$BACKUP_EXTENSION"
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
