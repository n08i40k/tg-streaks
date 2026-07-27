package ru.n08i40k.streaks.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import ru.n08i40k.streaks.util.runBlockingOnMainThread

suspend fun <T> Flow<T>.collectWith(collector: suspend T.() -> Unit) =
    collect { value -> collector.invoke(value) }

suspend fun <T> Flow<T>.collectOnUIThread(collector: FlowCollector<T>) =
    collect { value -> runBlockingOnMainThread { collector.emit(value) } }

suspend fun <T> Flow<T>.collectWithOnUIThread(collector: suspend T.() -> Unit) =
    collect { value -> runBlockingOnMainThread { collector.invoke(value) } }
