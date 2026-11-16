@file:Suppress("ObjectPropertyName", "HasPlatformType")

package ru.n08i40k.streaks.util

import org.telegram.tgnet.TLRPC
import ru.n08i40k.reflection.getFieldGetter
import ru.n08i40k.reflection.getFieldGetterIfExists
import ru.n08i40k.reflection.retype

// Accessors built from values of the generated ones (see Mirrors.kt)

@JvmField
val `TL_updateNewMessage$message` =
    getFieldGetter((`TL_update$TL_updateNewMessage$$12_8_0` ?: `TLRPC$TL_updateNewMessage$$12_1_1`)!!, "message")
        .retype(TLRPC.Message::class.java, Any::class.java)

@JvmField
val `SendMessageChatArguments$EMPTY` =
    getFieldGetterIfExists(SendMessageChatArguments, "EMPTY")
        ?.retype(Any::class.java)
        ?.invokeExact()
