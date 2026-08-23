package ru.n08i40k.streaks.extension

import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC

val TLRPC.User.label: String
    get() = this.username
        ?.takeIf { it.isNotBlank() }
        ?.let { "@$it" }
        ?: UserObject.getUserName(this)
            .takeIf { it.isNotBlank() }
        ?: this.id.toString()

val TLRPC.User.name: String
    get() = UserObject.getUserName(this)
        .takeIf { it.isNotBlank() }
        ?: this.username
            ?.takeIf { it.isNotBlank() }
        ?: this.id.toString()
