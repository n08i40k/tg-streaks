package ru.n08i40k.streaks.controller

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.database.dao.StreakEmojiPackDao
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.exception.InvalidStreakEmojiPackIdException
import java.util.UUID
import kotlin.time.Clock

class StreakEmojiPacksController(private val dao: StreakEmojiPackDao) {
    companion object {
        const val SHARED_PREFS_KEY = "active_streak_emoji_pack"
    }

    @Volatile
    private var currentPack = StreakEmojiPack.builtin[0]

    private suspend fun findById(id: UUID): StreakEmojiPack? {
        StreakEmojiPack.builtin
            .find { it.id == id }
            ?.let { return it }

        return dao.findById(id)
    }

    private suspend fun findByIdOrThrow(id: UUID): StreakEmojiPack =
        findById(id)
            ?: throw InvalidStreakEmojiPackIdException("Unable to find streak emoji pack with id $id")

    suspend fun init() {
        // fix broken packs
        val updatedPacks = dao.getAll()
            .mapNotNull { pack ->
                var newPack: StreakEmojiPack? = null

                val parent = StreakEmojiPack.builtin.find { it.id == pack.basedOn }
                    ?: run {
                        val parentPack = StreakEmojiPack.builtin[0]
                        newPack = pack.copy(basedOn = parentPack.id)

                        parentPack
                    }

                if (parent.emojis.keys != pack.emojis.keys) {
                    val emojis = parent.emojis.toMutableMap()

                    for ((key, value) in pack.emojis) {
                        // keep only valid entries
                        if (emojis.contains(key))
                            emojis[key] = value
                    }

                    newPack = (newPack ?: pack).copy(emojis = emojis)
                }

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

    fun getCurrent(): StreakEmojiPack = currentPack

    suspend fun getAll(): PersistentList<StreakEmojiPack> =
        (StreakEmojiPack.builtin + dao.getAll()).toPersistentList()

    suspend fun setCurrent(id: UUID): StreakEmojiPack {
        val pack = findByIdOrThrow(id)

        synchronized(this) {
            currentPack = pack

            with(Plugin.getSharedPrefs().edit()) {
                putString(SHARED_PREFS_KEY, pack.id.toString())
                apply()
            }
        }

        EventBus.emit(PluginEvent.ActiveStreakEmojiPackChanged(Clock.System.now()))

        return pack
    }

    suspend fun create(basedOn: UUID): StreakEmojiPack {
        val basePack = findByIdOrThrow(basedOn)

        if (!basePack.isBuiltin)
            throw IllegalArgumentException("Unable to create emoji pack based on non-builtin pack")

        val newId = UUID.randomUUID()
        val newPack = basePack.copy(id = newId, basedOn = basePack.id, name = newId.toString())

        dao.insertOrReplace(newPack)

        return newPack
    }

    suspend fun exists(id: UUID): Boolean = findById(id) != null

    // returns true if an already existing pack was replaced
    suspend fun import(pack: StreakEmojiPack): Boolean {
        if (pack.isBuiltin)
            throw IllegalArgumentException("Unable to import emoji pack with builtin id ${pack.id}")

        if (StreakEmojiPack.builtin.none { it.id == pack.basedOn })
            throw IllegalArgumentException("Unable to import emoji pack based on non-builtin pack ${pack.basedOn}")

        val replaced = dao.findById(pack.id) != null

        dao.insertOrReplace(pack)

        setCurrent(pack.id)

        return replaced
    }

    suspend fun update(pack: StreakEmojiPack) {
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
            EventBus.emit(PluginEvent.ActiveStreakEmojiPackChanged(Clock.System.now()))
    }

    suspend fun delete(id: UUID) {
        val pack = findByIdOrThrow(id)

        if (pack.isBuiltin)
            throw IllegalArgumentException("Unable to delete builtin pack")

        if (pack.id == currentPack.id)
            setCurrent(StreakEmojiPack.builtin[0].id)

        dao.delete(pack)
    }
}