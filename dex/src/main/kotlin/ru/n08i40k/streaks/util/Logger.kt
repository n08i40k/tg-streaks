package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.extension.format
import ru.n08i40k.streaks.ui.CrashBottomSheet

object Logger {
    @Volatile private var receiver: LogReceiver? = null
    @Volatile private var suppressFatal = false

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
        val formattedException = e.format()

        receiver?.onReceiveValue(message)
        receiver?.onReceiveValue(formattedException)

        if (!suppressFatal && !preventEject && Plugin.isInjected()) {
            CrashBottomSheet.show(message, exception)

            Plugin.eject()
        }
    }
}
