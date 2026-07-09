package ru.n08i40k.streaks.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.AndroidUtilities

suspend fun <T> Flow<T>.collectWith(collector: suspend T.() -> Unit) =
    collect { value -> collector(value) }

suspend fun <T> Flow<T>.collectOnUIThread(collector: FlowCollector<T>) =
    collect { value ->
        AndroidUtilities.runOnUIThread {
            runBlocking { collector.emit(value) }
        }
    }

suspend fun <T> Flow<T>.collectWithOnUIThread(collector: suspend T.() -> Unit) =
    collect { value ->
        AndroidUtilities.runOnUIThread {
            runBlocking { collector(value) }
        }
    }
