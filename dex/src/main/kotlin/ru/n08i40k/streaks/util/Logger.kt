package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin

class Logger(private val logReceiver: LogReceiver) {
    fun info(message: String) =
        logReceiver.onReceiveValue(message)

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        val e = exception as? Exception ?: Exception(exception)

        logReceiver.onReceiveValue(message)
        logReceiver.onReceiveValue(e.toString())
        logReceiver.onReceiveValue(e.stackTrace.joinToString("\n"))

        if (!preventEject)
            Plugin.eject()
    }
}