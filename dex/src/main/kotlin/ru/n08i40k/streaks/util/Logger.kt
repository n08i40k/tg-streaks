package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.ui.CrashBottomSheet

object Logger {
    @Volatile private var receiver: LogReceiver? = null
    @Volatile private var suppressFatal = false

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

    fun info(message: String) {
        receiver?.onReceiveValue(message)
    }

    fun setReceiver(receiver: LogReceiver?) {
        this.receiver = receiver
    }

    fun setFatalSuppression(value: Boolean) {
        suppressFatal = value
    }

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        val e = exception as? Exception ?: Exception(exception)
        val formattedException = e.formatWithCauses()

        receiver?.onReceiveValue(message)
        receiver?.onReceiveValue(formattedException)

        if (!suppressFatal && !preventEject && Plugin.isInjected()) {
            CrashBottomSheet.show(message, exception)

            Plugin.eject()
        }
    }
}
