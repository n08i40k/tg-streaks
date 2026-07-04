package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.LogReceiver
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.format
import ru.n08i40k.streaks.ui.CrashBottomSheet

object Logger : EjectNotifier.Delegate(1000) {
    @Volatile
    private var receiver: LogReceiver? = null

    @Volatile
    private var suppressFatal = false

    fun info(message: String) {
        receiver?.onReceiveValue(message)
    }

    fun setReceiver(receiver: LogReceiver) {
        this.receiver = receiver
    }

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        val e = exception as? Exception ?: Exception(exception)
        val formattedException = e.format()

        receiver?.onReceiveValue(message)
        receiver?.onReceiveValue(formattedException)

        if (!suppressFatal) {
            CrashBottomSheet.show(message, exception)

            if (!preventEject)
                Plugin.eject()
        }
    }

    fun tryOrFatal(action: String, block: () -> Unit): Unit? =
        try {
            block()
        } catch (e: Throwable) {
            fatal("Failed to $action", e)
            null
        }

    override fun onEject() {
        suppressFatal = true
        receiver = null
    }
}
