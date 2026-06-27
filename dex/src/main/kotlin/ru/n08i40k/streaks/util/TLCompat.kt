@file:Suppress("ClassName", "SpellCheckingInspection")

package ru.n08i40k.streaks.util

import org.telegram.tgnet.TLRPC

object TLCompat {
    data class TL_updateNewMessage(private val update: Any) {
        companion object {
            const val CLASS_NAME = "TL_updateNewMessage"

            private val klass by lazy {
                if (isClientVersionBelow("12.8.0"))
                    Class.forName("org.telegram.tgnet.TLRPC.$CLASS_NAME")
                else
                    Class.forName("org.telegram.tgnet.tl.TL_update.$CLASS_NAME")
            }

            private val messageField by lazy { klass.getField("message") }
        }

        val message: TLRPC.Message get() = messageField.get(update) as TLRPC.Message
    }
}