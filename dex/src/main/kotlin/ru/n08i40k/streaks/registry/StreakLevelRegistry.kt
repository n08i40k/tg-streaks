package ru.n08i40k.streaks.registry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import org.telegram.ui.Components.AnimatedEmojiDrawable
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds

class StreakLevelRegistry {
    private val _levels = sortedSetOf<StreakLevel>(compareBy { it.length })

    fun levels(): List<StreakLevel> =
        _levels.toList()

    fun findByLengthApproximate(length: Int): StreakLevel =
        _levels.findLast { it.length <= length } ?: _levels.last()

    fun findByLengthPrecise(length: Int): StreakLevel? =
        _levels.find { it.length == length }

    // Plugin.clearCaches and StreakLevelRegistry.precacheEmojis should be called after all regs
    fun register(level: StreakLevel) {
        if (findByLengthPrecise(level.length) != null)
            throw UnsupportedOperationException("Unable to overwrite existing streak")

        _levels.add(level)
    }

    suspend fun precacheEmojis() {
        val fetchers = userConfigAuthorizedIds.map { AnimatedEmojiDrawable.getDocumentFetcher(it) }
        val jobs = mutableListOf<Deferred<Unit>>()

        _levels.forEach { level ->
            fetchers.forEach { fetcher ->
                val deferred = CompletableDeferred<Unit>()
                jobs.add(deferred)

                fetcher.fetchDocument(level.documentId) {
                    deferred.complete(Unit)
                }
            }
        }

        jobs.awaitAll()
    }
}
