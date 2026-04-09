package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities
import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin

class Logger(private val logReceiver: LogReceiver) {
    private var suppressFatal = false

    private fun Throwable.formatWithCauses(): String {
        val builder = StringBuilder()
        var current: Throwable? = this
        var depth = 0

        while (current != null) {
            if (depth == 0) {
                builder.append(current.toString())
            } else {
                builder.append("\nCaused by: ").append(current.toString())
            }

            if (current.stackTrace.isNotEmpty()) {
                builder.append('\n')
                builder.append(current.stackTrace.joinToString("\n"))
            }

            current = current.cause
            depth++
        }

        return builder.toString()
    }

    fun info(message: String) =
        logReceiver.onReceiveValue(message)

    fun setFatalSuppression(value: Boolean) {
        suppressFatal = value
    }

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        val e = exception as? Exception ?: Exception(exception)
        val formattedException = e.formatWithCauses()

        logReceiver.onReceiveValue(message)
        logReceiver.onReceiveValue(formattedException)

        if (!suppressFatal && !preventEject && Plugin.isInjected()) {
            AndroidUtilities.addToClipboard(
                "```\n"
                        + "${message}\n"
                        + "${formattedException}\n"
                        + "```"
            )
            BulletinHelper.show(null, "Streaks plugin has been ejected!")
            Plugin.eject()
        }
    }
}
