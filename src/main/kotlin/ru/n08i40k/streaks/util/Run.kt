package ru.n08i40k.streaks.util

import android.os.Looper
import androidx.annotation.AnyThread
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.AndroidUtilities

@AnyThread
inline fun <R> runOnMainThread(crossinline block: () -> R) {
    RefCounter.inc()

    val wrappedBlock: () -> Unit = {
        try {
            block.invoke()
        } catch (e: Throwable) {
            Logger.fatal("run on UI thread", e)
        } finally {
            RefCounter.dec()
        }
    }

    if (Looper.myLooper() === Looper.getMainLooper())
        wrappedBlock.invoke()
    else
        AndroidUtilities.runOnUIThread(wrappedBlock)
}

@AnyThread
inline fun <R> runBlockingOnMainThread(crossinline block: suspend () -> R) =
    runOnMainThread { runBlocking { block.invoke() } }

