package ru.n08i40k.streaks.controller

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakLevelPack
import ru.n08i40k.streaks.database.dao.StreakLevelPackDao
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.exception.InvalidStreakLevelPackIdException
import java.util.UUID
import kotlin.time.Clock

class StreakLevelPacksController(private val dao: StreakLevelPackDao) {
    companion object {
        const val SHARED_PREFS_KEY = "active_streak_emoji_pack"
    }

    @Volatile
    private var currentPack = StreakLevelPack.builtin[0]

    private suspend fun findById(id: UUID): StreakLevelPack? {
        StreakLevelPack.builtin
            .find { it.id == id }
            ?.let { return it }

        return dao.findById(id)
    }

    private suspend fun findByIdOrThrow(id: UUID): StreakLevelPack =
        findById(id)
            ?: throw InvalidStreakLevelPackIdException("Unable to find streak level pack with id $id")

    suspend fun init() {
        // fix broken packs
        val updatedPacks = dao.getAll()
            .mapNotNull { pack ->
                var newPack: StreakLevelPack? = null

                val parent = StreakLevelPack.builtin.find { it.id == pack.basedOn }
                    ?: run {
                        val parentPack = StreakLevelPack.builtin[0]
                        newPack = pack.copy(basedOn = parentPack.id)

                        parentPack
                    }

                val emojis = pack.emojis
                    .filterKeys { StreakLevelPack.extractLength(it) >= 0 }
                    .toMutableMap()

                for (key in listOf("cold", "3d"))
                    emojis.putIfAbsent(key, parent.emojis.getValue(key))

                if (emojis != pack.emojis)
                    newPack = (newPack ?: pack).copy(emojis = emojis)

                return@mapNotNull newPack
            }

        dao.updateAll(updatedPacks)

        // load active pack
        val prefs = Plugin.getSharedPrefs()

        prefs.getString(SHARED_PREFS_KEY, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { findById(it) }
            ?.let { currentPack = it; return }

        // write default value, if it was not saved before or pack not found
        with(prefs.edit()) {
            putString(SHARED_PREFS_KEY, currentPack.id.toString())
            apply()
        }
    }

    fun getCurrent(): StreakLevelPack = currentPack

    suspend fun getAll(): PersistentList<StreakLevelPack> =
        (StreakLevelPack.builtin + dao.getAll()).toPersistentList()

    suspend fun setCurrent(id: UUID): StreakLevelPack {
        val pack = findByIdOrThrow(id)

        synchronized(this) {
            currentPack = pack

            with(Plugin.getSharedPrefs().edit()) {
                putString(SHARED_PREFS_KEY, pack.id.toString())
                apply()
            }
        }

        EventBus.emit(PluginEvent.ActiveStreakLevelPackChanged(Clock.System.now()))

        return pack
    }

    suspend fun create(basedOn: UUID): StreakLevelPack {
        val basePack = findByIdOrThrow(basedOn)

        if (!basePack.isBuiltin)
            throw IllegalArgumentException("Unable to create streak level pack based on non-builtin pack")

        val newId = UUID.randomUUID()
        val newPack =
            basePack.copy(id = newId, basedOn = basePack.id, name = newId.toString(), version = 2)

        dao.insertOrReplace(newPack)

        return newPack
    }

    suspend fun exists(id: UUID): Boolean = findById(id) != null

    // returns true if an already existing pack was replaced
    suspend fun import(pack: StreakLevelPack): Boolean {
        require(StreakLevelPack.isValid(pack)) { "Invalid streak levels" }

        if (pack.isBuiltin)
            throw IllegalArgumentException("Unable to import streak level pack with builtin id ${pack.id}")

        if (StreakLevelPack.builtin.none { it.id == pack.basedOn })
            throw IllegalArgumentException("Unable to import streak level pack based on non-builtin pack ${pack.basedOn}")

        val replaced = dao.findById(pack.id) != null

        dao.insertOrReplace(pack)

        setCurrent(pack.id)

        return replaced
    }

    suspend fun update(pack: StreakLevelPack) {
        require(StreakLevelPack.isValid(pack)) { "Invalid streak levels" }

        findByIdOrThrow(pack.id)

        if (pack.isBuiltin)
            throw IllegalArgumentException("Unable to update builtin pack")

        dao.update(pack)

        val isCurrent = synchronized(this) {
            if (pack.id != currentPack.id)
                return@synchronized false

            currentPack = pack
            true
        }

        if (isCurrent)
            EventBus.emit(PluginEvent.ActiveStreakLevelPackChanged(Clock.System.now()))
    }

    suspend fun delete(id: UUID) {
        val pack = findByIdOrThrow(id)

        if (pack.isBuiltin)
            throw IllegalArgumentException("Unable to delete builtin pack")

        if (pack.id == currentPack.id)
            setCurrent(StreakLevelPack.builtin[0].id)

        dao.delete(pack)
    }
}