@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.util

import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.Vector
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.extension.RequestOutcome
import ru.n08i40k.streaks.extension.fmt
import ru.n08i40k.streaks.extension.sendRequestBlocking


suspend fun fetchPeerUsers(
    accountId: Int,
    peerUserIds: ArrayList<Long>,
): Map<Long, TLRPC.User>? {
    val logger = Plugin.getInstance().logger

    val connectionsManager = ConnectionsManager.getInstance(accountId)
    val messagesController = MessagesStorage.getInstance(accountId)

    @Suppress("CAST_NEVER_SUCCEEDS")
    val req = TLRPC.TL_users_getUsers()
        .apply {
            id = ArrayList(
                peerUserIds.mapNotNull {
                    (TLRPC.TL_inputUser() as TLRPC.InputUser).apply {
                        user_id = it
                        access_hash = messagesController.getUserSync(it)?.access_hash
                            ?: return@mapNotNull null
                    }
                }
            )
        }

    return when (val result = connectionsManager.sendRequestBlocking(req, 10000)) {
        is RequestOutcome.Success -> result.cast<Vector>()
            .objects
            .map { it as TLRPC.User }
            .associateBy { it.id }

        is RequestOutcome.Failure -> throw RuntimeException(
            "Failed to fetch users for $accountId",
            Exception(result.error.fmt())
        )

        is RequestOutcome.RateLimit -> {
            logger.info("Users request for $accountId is rate-limited")
            null
        }

        is RequestOutcome.TimeOut -> {
            logger.info("Users request timed out for $accountId")
            null
        }
    }
}