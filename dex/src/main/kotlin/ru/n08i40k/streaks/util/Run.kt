package ru.n08i40k.streaks.util

import androidx.annotation.AnyThread
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.AndroidUtilities

@AnyThread
inline fun <R> runOnUIThread(crossinline block: () -> R) {
    RefCounter.inc()

    AndroidUtilities.runOnUIThread {
        try {
            block.invoke()
        } finally {
            RefCounter.dec()
        }
    }
}

@AnyThread
inline fun <R> runOnMainThread(crossinline block: () -> R) =
    runOnUIThread(block)

@AnyThread
inline fun <R> runBlockingOnUIThread(crossinline block: suspend () -> R) {
    RefCounter.inc()

    AndroidUtilities.runOnUIThread {
        try {
            runBlocking { block.invoke() }
        } finally {
            RefCounter.dec()
        }
    }
}

@AnyThread
inline fun <R> runBlockingOnMainThread(crossinline block: suspend () -> R) =
    runBlockingOnUIThread(block)
