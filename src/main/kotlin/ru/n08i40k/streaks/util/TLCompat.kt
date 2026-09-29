package ru.n08i40k.streaks.util

import org.telegram.tgnet.TLRPC

@Suppress("ClassName")
object TLCompat {
    data class TL_updateNewMessage(private val update: Any) {
        companion object {
            const val CLASS_NAME = "TL_updateNewMessage"
        }

        val message: TLRPC.Message
            get() = `TL_updateNewMessage$message`.invokeExact(update) as TLRPC.Message
    }
}