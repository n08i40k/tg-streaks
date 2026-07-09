package ru.n08i40k.streaks.chat_history_fetcher

import kotlinx.datetime.LocalDate
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.exception.InvalidPeerException
import ru.n08i40k.streaks.extension.RequestOutcome
import ru.n08i40k.streaks.extension.fmt
import ru.n08i40k.streaks.extension.isPeerIdInvalid
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.sendRequestBlocking
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RebuildNotificationHelper
import ru.n08i40k.streaks.util.RuntimeGuard


class RemoteChatHistoryFetcher : ChatHistoryFetcher {
    private companion object {
        const val HISTORY_BLOCK_SIZE = 10
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 15_000L
    }

    private suspend fun pauseWithRetryNotification(
        accountId: Int,
        peerUserId: Long,
        retryDelayMs: Long,
        reason: String,
    ) {
        val peerName = MessagesController
            .getInstance(accountId)
            .getUser(peerUserId)
            ?.label
            ?: peerUserId.toString()

        RuntimeGuard.pauseAwareDelay(retryDelayMs, reason) { remainingMs, totalMs ->
            RebuildNotificationHelper.showRateLimitCountdown(peerName, remainingMs, totalMs)
        }
        RebuildNotificationHelper.cancelRateLimitNotification()
    }

    private suspend fun requestHistory(
        accountId: Int,
        peerUserId: Long,
        connectionsManager: ConnectionsManager,
        req: TLRPC.TL_messages_getHistory,
    ): TLRPC.messages_Messages {
        var attempt = 0

        while (true) {
            RuntimeGuard.awaitAppForegroundAndConnection(
                accountId,
                "history fetch for $accountId:$peerUserId",
            )
            RuntimeGuard.pauseAwareDelay(
                200L,
                "history fetch backoff for $accountId:$peerUserId",
            )

            attempt++

            val result =
                connectionsManager.sendRequestBlocking(req, REQUEST_TIMEOUT_MS, RETRY_DELAY_MS)

            when (result) {
                is RequestOutcome.Success -> return result.cast<TLRPC.messages_Messages>()

                is RequestOutcome.Failure -> {
                    val formattedError = result.error.fmt()

                    if (result.error.isPeerIdInvalid()) {
                        throw InvalidPeerException(
                            accountId,
                            peerUserId,
                            "Invalid peer for history fetch $accountId:$peerUserId",
                            Exception(formattedError)
                        )
                    }

                    throw RuntimeException(
                        "Failed to fetch chat activity $accountId:$peerUserId",
                        Exception(formattedError)
                    )
                }

                is RequestOutcome.RateLimit -> {
                    Logger.info(
                        "History request for $accountId:$peerUserId is rate-limited " +
                                "(attempt $attempt), retrying in ${result.retryDelay / 1000}s"
                    )
                    pauseWithRetryNotification(
                        accountId, peerUserId, result.retryDelay,
                        "history retry delay for $accountId:$peerUserId",
                    )
                    continue
                }

                is RequestOutcome.TransientFailure -> {
                    Logger.info(
                        "History request failed temporarily with ${result.error.fmt()} for " +
                                "$accountId:$peerUserId (attempt $attempt), retrying in ${result.retryDelay / 1000}s"
                    )
                    pauseWithRetryNotification(
                        accountId, peerUserId, result.retryDelay,
                        "history transient retry delay for $accountId:$peerUserId",
                    )
                    continue
                }

                is RequestOutcome.TimeOut -> {
                    Logger.info(
                        "History request timed out after ${REQUEST_TIMEOUT_MS / 1000}s for " +
                                "$accountId:$peerUserId (attempt $attempt), retrying in ${RETRY_DELAY_MS / 1000}s"
                    )
                    pauseWithRetryNotification(
                        accountId, peerUserId, RETRY_DELAY_MS,
                        "history retry delay for $accountId:$peerUserId",
                    )
                    continue
                }
            }
        }
    }

    @Throws(RuntimeException::class)
    override suspend fun fetchActivity(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        untilRevive: Boolean
    ): ChatHistoryFetcher.Status {
        val startLocalEpoch = day.toEpochSecondSystem().toInt()
        var endLocalEpoch = day.next().toEpochSecondSystem().toInt()
        var offsetId = 0

        var fromOwner = false
        var fromPeer = false
        var wasRevived = false

        val inputPeer = MessagesController.getInstance(accountId).getInputPeer(peerUserId)
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        reqLoop@ while (true) {
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = inputPeer
                offset_id = offsetId
                offset_date = endLocalEpoch
                add_offset = if (offsetId != 0) 1 else 0
                limit = HISTORY_BLOCK_SIZE
            }

            val res = requestHistory(accountId, peerUserId, connectionsManager, req)

            if (res.messages.isEmpty())
                break@reqLoop // break loop if no messages found (probably start of the chat?)

            for (message in res.messages) {
                if (message.date !in startLocalEpoch..endLocalEpoch)
                    break@reqLoop // no more messages for today

                if (message.message == ServiceMessage.RESTORE_TEXT) {
                    wasRevived = true

                    if (fromOwner && fromPeer && untilRevive)
                        break@reqLoop // no need to check other messages more

                    continue
                }

                if (ServiceMessage.isServiceText(message.message))
                    continue

                if (message.out)
                    fromOwner = true
                else
                    fromPeer = true

                if (fromOwner && fromPeer && (!untilRevive || wasRevived))
                    break@reqLoop // no need to check other messages more
            }

            val oldestMessage = res.messages.lastOrNull() ?: break@reqLoop
            if (oldestMessage.date == endLocalEpoch && oldestMessage.id == offsetId) {
                Logger.info(
                    "History cursor stalled for $accountId:$peerUserId on $day " +
                            "(offset_date=$endLocalEpoch, offset_id=$offsetId), stopping to avoid loop"
                )
                break@reqLoop
            }

            endLocalEpoch = oldestMessage.date
            offsetId = oldestMessage.id

            if (res.messages.size < HISTORY_BLOCK_SIZE)
                break@reqLoop // no more messages for next request will be returned
        }

        return when {
            fromOwner && fromPeer -> ChatHistoryFetcher.Status.FromBoth(wasRevived)
            fromOwner -> ChatHistoryFetcher.Status.FromOwner(wasRevived)
            fromPeer -> ChatHistoryFetcher.Status.FromPeer(wasRevived)
            else -> ChatHistoryFetcher.Status.NoActivity(wasRevived)
        }
    }

    override suspend fun fetchRawMessages(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        fromOwnerMax: Int,
        fromPeerMax: Int,
    ): List<TLRPC.Message> {
        val startLocalEpoch = day.toEpochSecondSystem().toInt()
        var endLocalEpoch = day.next().toEpochSecondSystem().toInt()
        var offsetId = 0

        val messages = arrayListOf<TLRPC.Message>()

        val inputPeer = MessagesController.getInstance(accountId).getInputPeer(peerUserId)
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        var fromOwnerCount = 0
        var fromPeerCount = 0

        reqLoop@ while (true) {
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = inputPeer
                offset_id = offsetId
                offset_date = endLocalEpoch
                add_offset = if (offsetId != 0) 1 else 0
                limit = HISTORY_BLOCK_SIZE * 2
            }

            val res = requestHistory(accountId, peerUserId, connectionsManager, req)

            if (res.messages.isEmpty())
                break@reqLoop // break loop if no messages found (probably start of the chat?)

            for (message in res.messages) {
                if (message.date !in startLocalEpoch..endLocalEpoch)
                    break@reqLoop // no more messages for today

                messages.add(message)

                if (message.out)
                    ++fromOwnerCount
                else
                    ++fromPeerCount
            }

            if (fromOwnerCount >= fromOwnerMax && fromPeerCount >= fromPeerMax)
                break@reqLoop

            val oldestMessage = res.messages.lastOrNull() ?: break@reqLoop
            if (oldestMessage.date == endLocalEpoch && oldestMessage.id == offsetId) {
                Logger.info(
                    "History ids cursor stalled for $accountId:$peerUserId on $day " +
                            "(offset_date=$endLocalEpoch, offset_id=$offsetId), stopping to avoid loop"
                )
                break@reqLoop
            }

            endLocalEpoch = oldestMessage.date
            offsetId = oldestMessage.id
        }

        return messages.toList()
    }
}
