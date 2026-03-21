@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS")

package ru.n08i40k.streaks.database

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakRevive
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LegacyUsersDbImporter(
    private val db: PluginDatabase,
    private val logger: (String) -> Unit,
) {
    companion object {
        private const val PREFERENCES_NAME = "tg-streaks-legacy-users-db-import"
        private const val IMPORTED_KEY = "imported"
    }

    private data class ParsedLegacyRecord(
        val ownerUserId: Long,
        val peerUserId: Long,
        val createdAt: LocalDate,
        val updateFromOwnerAt: LocalDate,
        val updateFromPeerAt: LocalDate,
        val restoredDays: List<LocalDate>,
    ) {
        fun toStreak(): Streak =
            Streak(
                ownerUserId = ownerUserId,
                peerUserId = peerUserId,
                createdAt = createdAt,
                updateFromOwnerAt = updateFromOwnerAt,
                updateFromPeerAt = updateFromPeerAt,
                revivesCount = restoredDays.size,
            )
    }

    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        ApplicationLoader.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    private val streakDao by lazy(LazyThreadSafetyMode.NONE) { db.streakDao() }
    private val reviveDao by lazy(LazyThreadSafetyMode.NONE) { db.streakReviveDao() }

    suspend fun importIfNeeded(): Boolean {
        if (preferences.getBoolean(IMPORTED_KEY, false))
            return false

        val sourceFile = resolveSourceFile() ?: return false

        preferences.edit()
            .putBoolean(IMPORTED_KEY, true)
            .apply()

        try {
            val payload = JSONObject(sourceFile.readText())
            val version = payload.optInt("version", 1).coerceAtLeast(1)
            val users = payload.optJSONArray("users") ?: JSONArray()
            val importedCount = importPayload(version, users)

            logger(
                "Legacy users_db import completed: imported=$importedCount, version=$version, source=${sourceFile.absolutePath}"
            )
            return importedCount > 0
        } catch (t: Throwable) {
            logger("Legacy users_db import failed: ${t.message ?: t}")
            return false
        }
    }

    private suspend fun importPayload(version: Int, users: JSONArray): Int =
        db.withTransaction {
            val recordsByRelation = LinkedHashMap<Pair<Long, Long>, ParsedLegacyRecord>()

            for (index in 0 until users.length()) {
                val row = users.optJSONObject(index) ?: continue
                val parsedRows =
                    if (version >= 2) parseVersion2Record(row)
                    else parseVersion1Records(row)

                for (parsed in parsedRows) {
                    recordsByRelation[parsed.ownerUserId to parsed.peerUserId] = parsed
                }
            }

            var importedCount = 0

            for (record in recordsByRelation.values) {
                if (!insertIfMissing(record))
                    continue

                importedCount += 1
            }

            importedCount
        }

    private suspend fun insertIfMissing(record: ParsedLegacyRecord): Boolean {
        if (streakDao.findByRelation(record.ownerUserId, record.peerUserId) != null)
            return false

        streakDao.insertAll(record.toStreak())

        record.restoredDays.forEach { restoredAt ->
            reviveDao.insertAll(
                StreakRevive(
                    ownerUserId = record.ownerUserId,
                    peerUserId = record.peerUserId,
                    revivedAt = restoredAt,
                )
            )
        }

        return true
    }

    private fun parseVersion2Record(row: JSONObject): List<ParsedLegacyRecord> {
        val accountId = row.optInt("account_id", -1)
        val ownerUserId = resolveOwnerUserId(accountId) ?: return emptyList()
        val baseRecord = parseBaseRecord(row) ?: return emptyList()

        return listOf(baseRecord.copy(ownerUserId = ownerUserId))
    }

    private fun parseVersion1Records(row: JSONObject): List<ParsedLegacyRecord> {
        val baseRecord = parseBaseRecord(row) ?: return emptyList()

        return resolveLegacyOwnerUserIds(baseRecord.peerUserId)
            .map { ownerUserId -> baseRecord.copy(ownerUserId = ownerUserId) }
    }

    private fun parseBaseRecord(row: JSONObject): ParsedLegacyRecord? {
        val peerUserId = row.optLong("user_id", 0L)
        val createdAtSeconds = row.optLong("started_at", 0L)

        if (peerUserId <= 0L || createdAtSeconds <= 0L)
            return null

        val lastSentSeconds = row.optNullableLong("last_sended_at")
        val lastReceivedSeconds = row.optNullableLong("last_received_at")
        val restoredDays = normalizeRestoredDays(row.optJSONArray("restored_days"))

        val ownerActivitySeconds =
            lastSentSeconds ?: lastReceivedSeconds ?: createdAtSeconds
        val peerActivitySeconds =
            lastReceivedSeconds ?: lastSentSeconds ?: createdAtSeconds

        return ParsedLegacyRecord(
            ownerUserId = 0L,
            peerUserId = peerUserId,
            createdAt = epochSecondsToLocalDate(createdAtSeconds),
            updateFromOwnerAt = epochSecondsToLocalDate(ownerActivitySeconds),
            updateFromPeerAt = epochSecondsToLocalDate(peerActivitySeconds),
            restoredDays = restoredDays,
        )
    }

    private fun normalizeRestoredDays(days: JSONArray?): List<LocalDate> {
        if (days == null)
            return emptyList()

        val seen = linkedSetOf<LocalDate>()

        for (index in 0 until days.length()) {
            val epochSeconds = when (val value = days.opt(index)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            } ?: continue

            if (epochSeconds <= 0L)
                continue

            seen.add(epochSecondsToLocalDate(epochSeconds))
        }

        return seen.sorted()
    }

    private fun resolveLegacyOwnerUserIds(peerUserId: Long): List<Long> {
        val ownerUserIds = linkedSetOf<Long>()

        for (accountId in userConfigAuthorizedIds.sorted()) {
            val hasPeer = try {
                MessagesController.getInstance(accountId).getInputPeer(peerUserId) != null
            } catch (_: Throwable) {
                false
            }

            if (!hasPeer)
                continue

            resolveOwnerUserId(accountId)?.let(ownerUserIds::add)
        }

        if (ownerUserIds.isEmpty()) {
            resolveOwnerUserId(UserConfig.selectedAccount)?.let(ownerUserIds::add)
        }

        return ownerUserIds.toList()
    }

    private fun resolveOwnerUserId(accountId: Int): Long? {
        if (accountId < 0)
            return null

        return UserConfig.getInstance(accountId)
            .clientUserId
            .takeIf { it > 0L }
    }

    private fun resolveSourceFile(): File? {
        val storageDir = File(
            ApplicationLoader.applicationContext.filesDir,
            "chaquopy/plugins/tg-streaks"
        )
        val primaryFile = File(storageDir, "users_db.json")

        if (primaryFile.isFile)
            return primaryFile

        return File(storageDir, "backups")
            .listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.maxByOrNull(File::lastModified)
    }

    private fun epochSecondsToLocalDate(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name)) null
        else when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
}
