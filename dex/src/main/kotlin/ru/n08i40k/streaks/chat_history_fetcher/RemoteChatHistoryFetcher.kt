@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS")

package ru.n08i40k.streaks.chat_history_fetcher

import kotlinx.coroutines.CompletableDeferred
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSecondUtc
import ru.n08i40k.streaks.util.MyResult
import java.time.LocalDate


class RemoteChatHistoryFetcher : ChatHistoryFetcher {
    private companion object {
        const val HISTORY_BLOCK_SIZE = 10
    }

    @Throws(RuntimeException::class)
    override suspend fun fetch(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        untilRevive: Boolean
    ): ChatHistoryFetcher.Status {
        val startLocalEpoch = day.toEpochSecondUtc()
        val endLocalEpoch = day.next().toEpochSecondUtc()

        var fromOwner = false
        var fromPeer = false
        var wasRevived = false

        var lastMessageId = 0

        val peer = MessagesController.getInstance(accountId).getInputPeer(peerUserId)
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        reqLoop@ while ((!fromOwner || !fromPeer) || (untilRevive && !wasRevived)) {
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_id = lastMessageId
                offset_date = startLocalEpoch.toInt()
                limit = HISTORY_BLOCK_SIZE
            }

            val deferred = CompletableDeferred<MyResult<TLObject, TLRPC.TL_error>>()

            connectionsManager.sendRequest(req) { response, error ->
                deferred.complete(
                    when {
                        error == null -> MyResult.Ok(response)
                        else -> MyResult.Err(error)
                    }
                )
            }

            @Suppress("CAST_NEVER_SUCCEEDS")
            val res = when (val result = deferred.await()) {
                is MyResult.Err -> {
                    throw RuntimeException(
                        "Failed to fetch chat activity $accountId:$peerUserId",
                        Exception(result.error.toString())
                    )
                }

                is MyResult.Ok -> result.value
            } as TLRPC.messages_Messages

            if (res.messages.isEmpty())
                break@reqLoop // break loop if no messages found (probably start of the chat?)

            for (message in res.messages) {
                val message = message as? TLRPC.Message ?: continue

                if (message.date !in startLocalEpoch..endLocalEpoch)
                    break@reqLoop // no more messages for today

                if (message.from_id.user_id == peerUserId)
                    fromPeer = true
                else
                    fromOwner = true

                if (message.message == ServiceMessage.RESTORE_TEXT)
                    wasRevived = true

                lastMessageId = message.id

                if (fromOwner && fromPeer && (!untilRevive || wasRevived))
                    break@reqLoop // no need to check other messages more
            }

            if (res.messages.size < HISTORY_BLOCK_SIZE)
                break@reqLoop // no more messages for next request will be returned
        }

        return when {
            fromOwner && fromPeer -> ChatHistoryFetcher.Status.FromBoth(wasRevived)
            fromOwner -> ChatHistoryFetcher.Status.FromOwner(wasRevived)
            fromPeer -> ChatHistoryFetcher.Status.FromPeer(wasRevived)
            else -> ChatHistoryFetcher.Status.NoActivity()
        }
    }
}
