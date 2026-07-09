package ru.n08i40k.streaks.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val flow = MutableSharedFlow<PluginEvent>(extraBufferCapacity = 32)
    val stream: SharedFlow<PluginEvent> = flow.asSharedFlow()

    suspend fun emit(vararg events: PluginEvent) {
        for (event in events) {
            flow.emit(event)
        }
    }
}
