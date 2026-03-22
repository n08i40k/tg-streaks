package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities
import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin

class Logger(private val logReceiver: LogReceiver) {
    private var suppressFatal = false

    fun info(message: String) =
        logReceiver.onReceiveValue(message)

    fun setFatalSuppression(value: Boolean) {
        suppressFatal = value
    }

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        val e = exception as? Exception ?: Exception(exception)

        logReceiver.onReceiveValue(message)
        logReceiver.onReceiveValue(e.toString())
        logReceiver.onReceiveValue(e.stackTrace.joinToString("\n"))

        if (!suppressFatal && !preventEject && Plugin.isInjected()) {
            AndroidUtilities.addToClipboard(
                "```\n"
                        + "${message}\n"
                        + "${e.toString()}\n"
                        + "${e.stackTrace.joinToString("\n")}\n"
                        + "```"
            )
            Plugin.eject()
        }
    }
}