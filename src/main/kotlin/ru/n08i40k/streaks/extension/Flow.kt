package ru.n08i40k.streaks.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.onEach
import ru.n08i40k.streaks.util.runBlockingOnMainThread
import ru.n08i40k.streaks.util.runOnMainThread

fun <T> Flow<T>.onEachWith(action: suspend T.() -> Unit): Flow<T> =
    onEach { value -> action.invoke(value) }

fun <T> Flow<T>.onEachOnMainThread(action: (T) -> Unit): Flow<T> =
    onEach { value -> runOnMainThread { action.invoke(value) } }

fun <T> Flow<T>.onEachWithOnMainThread(action: T.() -> Unit): Flow<T> =
    onEach { value -> runOnMainThread { action.invoke(value) } }

fun <T> Flow<T>.onEachWithOnMainThreadBlocking(action: suspend T.() -> Unit): Flow<T> =
    onEach { value -> runBlockingOnMainThread { action.invoke(value) } }

suspend fun <T> Flow<T>.collectWith(collector: suspend T.() -> Unit) =
    collect { value -> collector.invoke(value) }

suspend fun <T> Flow<T>.collectOnMainThread(collector: FlowCollector<T>) =
    collect { value -> runBlockingOnMainThread { collector.emit(value) } }

suspend fun <T> Flow<T>.collectWithOnMainThread(collector: suspend T.() -> Unit) =
    collect { value -> runBlockingOnMainThread { collector.invoke(value) } }
