package ru.n08i40k.streaks.database

import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.data.PeerTimeZone
import ru.n08i40k.streaks.data.PluginRelation
import ru.n08i40k.streaks.data.ServiceMessageCategories
import ru.n08i40k.streaks.data.StreakPetTaskPayload
import ru.n08i40k.streaks.extension.buildPluginDatabase
import ru.n08i40k.streaks.util.RuntimeGuard
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DatabaseBackupManager(
    private val db: PluginDatabase,
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

    private val lock = Mutex()

    suspend fun exportNow(): File = lock.withLock {
        val backup = createBackup("manual")
        logger("Database backup exported: ${backup.absolutePath}")
        backup
    }

    suspend fun exportSwappedNow(ownerUserId: Long, peerUserId: Long): File = lock.withLock {
        val backupsDir = backupsDir()

        val timestamp = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
            .format(BACKUP_NAME_FORMAT)

        val backup = File(
            backupsDir,
            "tg-streaks-$timestamp-sync-$ownerUserId-$peerUserId.$BACKUP_EXTENSION"
        )

        val source = db
        val target = Room.buildPluginDatabase(backup.path)

        // relation
        // we can skip relation check, because sync can't be sent to user without plugin
        target
            .pluginRelationDao()
            .insertOrReplace(PluginRelation(peerUserId, ownerUserId, true))

        // time zone
        val timeZone = source
            .peerTimeZoneDao()
            .findByRelation(ownerUserId, peerUserId)
            ?: PeerTimeZone(ownerUserId, peerUserId, TimeZone.currentSystemDefault())

        target.peerTimeZoneDao()
            .insertOrReplace(timeZone.copy(ownerUserId = peerUserId, peerUserId = ownerUserId))

        // service message cats
        val serviceMessageCategories = source
            .serviceMessageCategoriesDao()
            .findByRelation(ownerUserId, peerUserId)
            ?: ServiceMessageCategories(
                ownerUserId,
                peerUserId,
                lifecycle = true,
                levelUp = true,
                pet = true,
                sync = true
            )

        target
            .serviceMessageCategoriesDao()
            .insertOrReplace(
                serviceMessageCategories.copy(
                    ownerUserId = peerUserId,
                    peerUserId = ownerUserId
                )
            )

        // streak
        source
            .streakDao()
            .findByRelation(ownerUserId, peerUserId)
            ?.let {
                it.copy(
                    ownerUserId = peerUserId,
                    peerUserId = ownerUserId,
                    updateFromOwnerAt = it.updateFromPeerAt,
                    updateFromPeerAt = it.updateFromOwnerAt
                )
            }
            ?.let { target.streakDao().insertOrReplace(it) }

        // + restores
        source
            .streakRestoreDao()
            .findByRelation(ownerUserId, peerUserId)
            .map { it.copy(ownerUserId = peerUserId, peerUserId = ownerUserId) }
            .let { target.streakRestoreDao().insertOrReplaceAll(it) }

        // streak pet
        source
            .streakPetDao()
            .findByRelation(ownerUserId, peerUserId)
            ?.copy(ownerUserId = peerUserId, peerUserId = ownerUserId)
            ?.let { target.streakPetDao().insertOrReplace(it) }

        // + tasks
        source
            .streakPetTaskDao()
            .findAllByRelation(ownerUserId, peerUserId)
            .map {
                val dbPayload = it.dbPayload?.let { p ->
                    when (p) {
                        is StreakPetTaskPayload.ExchangeOneMessage ->
                            p.copy(
                                fromOwnerMessageId = p.fromPeerMessageId,
                                fromPeerMessageId = p.fromOwnerMessageId
                            )

                        is StreakPetTaskPayload.SendFourMessagesEach ->
                            p.copy(
                                fromOwnerMessagesCount = p.fromPeerMessagesCount,
                                fromOwnerLastMessageId = p.fromPeerLastMessageId,
                                fromPeerMessagesCount = p.fromOwnerMessagesCount,
                                fromPeerLastMessageId = p.fromOwnerLastMessageId
                            )

                        is StreakPetTaskPayload.SendTenMessagesEach ->
                            p.copy(
                                fromOwnerMessagesCount = p.fromPeerMessagesCount,
                                fromOwnerLastMessageId = p.fromPeerLastMessageId,
                                fromPeerMessagesCount = p.fromOwnerMessagesCount,
                                fromPeerLastMessageId = p.fromOwnerLastMessageId
                            )
                    }
                }

                it.copy(ownerUserId = peerUserId, peerUserId = ownerUserId, dbPayload = dbPayload)
            }
            .let { target.streakPetTaskDao().insertOrReplaceAll(it) }

        target.useWriterConnection { transactor ->
            transactor.usePrepared("PRAGMA wal_checkpoint(FULL)") { it.step() }
        }

        target.close()

        return backup
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

    private suspend fun ensureAutoBackupIfDue() {
        val now = Clock.System.now()
        val lastAutoBackupAt = lastAutoBackupAt()

        if (
            lastAutoBackupAt != null &&
            now - lastAutoBackupAt < AUTO_BACKUP_INTERVAL
        ) {
            return
        }

        lock.withLock {
            val refreshedLastAutoBackupAt = lastAutoBackupAt()
            val refreshedNow = Clock.System.now()

            if (
                refreshedLastAutoBackupAt != null &&
                refreshedNow - refreshedLastAutoBackupAt < AUTO_BACKUP_INTERVAL
            ) {
                return@withLock
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
        val lastAutoBackupAt =
            lastAutoBackupAt() ?: return AUTO_BACKUP_INTERVAL.inWholeMilliseconds
        val elapsed = Clock.System.now() - lastAutoBackupAt
        val remaining = AUTO_BACKUP_INTERVAL - elapsed
        return remaining.inWholeMilliseconds.coerceAtLeast(60_000L)
    }

    private suspend fun createBackup(source: String): File {
        val backupsDir = backupsDir()

        val timestamp = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
            .format(BACKUP_NAME_FORMAT)

        val backup = File(
            backupsDir,
            "tg-streaks-$timestamp-$source.$BACKUP_EXTENSION"
        )
        val target = context.getDatabasePath(DATABASE_NAME)

        // Hold Room's writer connection for the whole checkpoint + copy so
        // concurrent write transactions wait instead of failing with SQLITE_BUSY,
        // and the copied file can't be modified mid-copy.
        db.useWriterConnection { transactor ->
            transactor.usePrepared("PRAGMA wal_checkpoint(FULL)") { it.step() }
            target.copyTo(backup, overwrite = true)
        }

        pruneOldBackups(backupsDir)
        return backup
    }

    private fun backupsDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "tg-streaks"
        ).apply { mkdirs() }

    private fun pruneOldBackups(backupsDir: File) {
        backupsDir.listFiles()
            ?.filter { it.isFile && it.extension == BACKUP_EXTENSION }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_BACKUPS)
            ?.forEach(File::delete)
    }

    suspend fun importSwappedNow(sourceFile: File, ownerUserId: Long, peerUserId: Long) {
        logger("Importing swapped database snapshot from $peerUserId for $ownerUserId")
        logger("File ${sourceFile.absolutePath}")

        val source = Room.buildPluginDatabase(sourceFile.path)
        val target = db

        target.withTransaction {
            target.pluginRelationDao().apply {
                source.pluginRelationDao().findByRelation(ownerUserId, peerUserId)
                    ?.let { insertOrReplace(it) }
            }

            target.peerTimeZoneDao().apply {
                source.peerTimeZoneDao().findByRelation(ownerUserId, peerUserId)
                    ?.let { insertOrReplace(it) }
            }

            target.serviceMessageCategoriesDao().apply {
                source.serviceMessageCategoriesDao().findByRelation(ownerUserId, peerUserId)
                    ?.let { insertOrReplace(it) }
            }

            target.streakDao().apply {
                source.streakDao().findByRelation(ownerUserId, peerUserId)
                    ?.let { insertOrReplace(it) }
            }

            target.streakRestoreDao().apply {
                deleteByRelation(ownerUserId, peerUserId)

                source.streakRestoreDao().findByRelation(ownerUserId, peerUserId)
                    .let { insertOrReplaceAll(it) }
            }

            target.streakPetDao().apply {
                source.streakPetDao().findByRelation(ownerUserId, peerUserId)
                    ?.let { insertOrReplace(it) }
            }

            target.streakPetTaskDao().apply {
                deleteByRelation(ownerUserId, peerUserId)

                source.streakPetTaskDao().findAllByRelation(ownerUserId, peerUserId)
                    .let { insertOrReplaceAll(it) }
            }
        }

        source.close()
    }
}
