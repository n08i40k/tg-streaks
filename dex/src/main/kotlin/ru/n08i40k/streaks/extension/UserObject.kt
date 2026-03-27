@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.extension

import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC

fun isPeerValid(accountId: Int, peerUserId: Long): Boolean =
    isPeerValid(MessagesController.getInstance(accountId).getUser(peerUserId))

fun isPeerValid(peerUser: TLRPC.User?): Boolean =
    peerUser != null
            && !UserObject.isUserSelf(peerUser)
            && !UserObject.isAnonymous(peerUser)
            && !UserObject.isBot(peerUser)
            && !UserObject.isDeleted(peerUser)
            && !UserObject.isService(peerUser.id)
            && !UserObject.isReplyUser(peerUser)


fun isPeerValidOrBot(accountId: Int, peerUserId: Long): Boolean =
    isPeerValidOrBot(MessagesController.getInstance(accountId).getUser(peerUserId))

fun isPeerValidOrBot(peerUser: TLRPC.User?): Boolean =
    peerUser != null
            && !UserObject.isUserSelf(peerUser)
            && !UserObject.isAnonymous(peerUser)
            && !UserObject.isDeleted(peerUser)
            && !UserObject.isService(peerUser.id)
            && !UserObject.isReplyUser(peerUser)

enum class PeerType {
    VALID,
    INVALID,
    BOT,
    ;
}

fun getPeerType(accountId: Int, peerUserId: Long): PeerType =
    getPeerType(MessagesController.getInstance(accountId).getUser(peerUserId))

fun getPeerType(peerUser: TLRPC.User?): PeerType =
    when {
        peerUser == null
                || UserObject.isUserSelf(peerUser)
                || UserObject.isAnonymous(peerUser)
                || UserObject.isDeleted(peerUser)
                || UserObject.isService(peerUser.id)
                || UserObject.isReplyUser(peerUser) -> PeerType.INVALID

        UserObject.isBot(peerUser) -> PeerType.BOT

        else -> PeerType.VALID
    }
