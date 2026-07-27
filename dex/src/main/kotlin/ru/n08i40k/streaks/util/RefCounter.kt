package ru.n08i40k.streaks.util

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
object RefCounter {
    private val lock = Unit

    private var counter = AtomicInt(0)

    @Volatile
    private var subscriber: CompletableDeferred<Unit>? = null

    fun inc() {
        counter.incrementAndFetch()
    }

    fun dec() {
        synchronized(lock) {
            if (counter.decrementAndFetch() == 0)
                subscriber?.complete(Unit)
        }
    }

    suspend fun wait() {
        if (subscriber != null)
            throw IllegalStateException("RefCounter wait can be invoked only once per plugin instance")

        val sub = CompletableDeferred<Unit>()

        synchronized(lock) {
            if (counter.load() == 0)
                return

            subscriber = sub
        }

        sub.await()
    }
}