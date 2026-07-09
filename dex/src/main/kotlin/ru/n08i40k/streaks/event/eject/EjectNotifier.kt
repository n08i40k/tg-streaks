package ru.n08i40k.streaks.event.eject

import androidx.annotation.AnyThread
import java.util.concurrent.CopyOnWriteArrayList

object EjectNotifier {
    private data class Listener(val priority: Int, val callback: () -> Unit)

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(priority: Int = 0, listener: () -> Unit): () -> Unit {
        val entry = Listener(priority, listener)
        listeners.add(entry)

        return { listeners.remove(entry) }
    }

    fun fire() {
        listeners.sortedBy { it.priority }.forEach { it.callback() }
        listeners.clear()
    }

    interface Delegate {
        @AnyThread
        fun onEject()
    }

    fun subscribe(delegate: Delegate, priority: Int = 0): () -> Unit =
        subscribe(priority, delegate::onEject)
}