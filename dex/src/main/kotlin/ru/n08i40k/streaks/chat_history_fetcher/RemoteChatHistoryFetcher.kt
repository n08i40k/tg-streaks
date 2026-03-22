@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS")

package ru.n08i40k.streaks.chat_history_fetcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toEpochSecondUtc
import java.time.LocalDate


class RemoteChatHistoryFetcher : ChatHistoryFetcher {
    private sealed class RequestOutcome {
        data class Success(val response: TLObject) : RequestOutcome()
        data class Failure(val error: TLRPC.TL_error) : RequestOutcome()
        data object Empty : RequestOutcome()
    }

    private companion object {
        const val HISTORY_BLOCK_SIZE = 10
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 15_000L
        val FLOOD_WAIT_REGEX = Regex("""FLOOD_WAIT_(\d+)""")
    }

    private fun TLRPC.TL_error.isRetryable(): Boolean {
        val text = this.text.orEmpty()

        return text.startsWith("FLOOD_WAIT_")
                || text.contains("RATE_LIMIT", ignoreCase = true)
                || text.contains("TOO_MANY_REQUESTS", ignoreCase = true)
                || code == 429
    }

    private fun TLRPC.TL_error.retryDelayMs(): Long {
        val floodWaitSeconds = FLOOD_WAIT_REGEX.find(this.text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

        return maxOf(RETRY_DELAY_MS, (floodWaitSeconds ?: 0L) * 1000L)
    }

    private fun showRetryBulletin(retryDelayMs: Long) {
        Plugin.getInstance().bulletinHelper.showTranslated(
            TranslationKey.FORCE_CHECK_RETRY_DELAY,
            mapOf("seconds" to (retryDelayMs / 1000L).toString()),
            "msg_retry"
        )
    }

    private suspend fun requestHistory(
        accountId: Int,
        peerUserId: Long,
        connectionsManager: ConnectionsManager,
        req: TLRPC.TL_messages_getHistory
    ): TLRPC.messages_Messages {
        var attempt = 0

        while (true) {
            delay(200)

            attempt++

            val deferred = CompletableDeferred<RequestOutcome>()

            val requestId = connectionsManager.sendRequest(req) { response, error ->
                deferred.complete(
                    when {
                        error != null -> RequestOutcome.Failure(error)
                        response != null -> RequestOutcome.Success(response)
                        else -> RequestOutcome.Empty
                    }
                )
            }

            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }

            if (result == null) {
                connectionsManager.cancelRequest(requestId, true)

                Plugin.getInstance().logger.info(
                    "History request timed out after ${REQUEST_TIMEOUT_MS / 1000}s for " +
                            "$accountId:$peerUserId (attempt $attempt), retrying in ${RETRY_DELAY_MS / 1000}s"
                )

                showRetryBulletin(RETRY_DELAY_MS)

                delay(RETRY_DELAY_MS)
                continue
            }

            when (result) {
                is RequestOutcome.Success -> return result.response as TLRPC.messages_Messages

                is RequestOutcome.Empty -> {
                    Plugin.getInstance().logger.info(
                        "History request returned empty response for $accountId:$peerUserId " +
                                "(attempt $attempt), retrying in ${RETRY_DELAY_MS / 1000}s"
                    )

                    showRetryBulletin(RETRY_DELAY_MS)

                    delay(RETRY_DELAY_MS)
                    continue
                }

                is RequestOutcome.Failure -> {
                    if (result.error.isRetryable()) {
                        val retryDelayMs = result.error.retryDelayMs()

                        Plugin.getInstance().logger.info(
                            "History request rate-limited for $accountId:$peerUserId " +
                                    "(attempt $attempt, code=${result.error.code}, text=${result.error.text}), " +
                                    "retrying in ${retryDelayMs / 1000}s"
                        )

                        showRetryBulletin(retryDelayMs)

                        delay(retryDelayMs)
                        continue
                    }

                    throw RuntimeException(
                        "Failed to fetch chat activity $accountId:$peerUserId",
                        Exception(result.error.toString())
                    )
                }
            }
        }
    }

    @Throws(RuntimeException::class)
    override suspend fun fetch(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        untilRevive: Boolean
    ): ChatHistoryFetcher.Status {
        val startLocalEpoch = day.toEpochSecondUtc().toInt()
        var endLocalEpoch = day.next().toEpochSecondUtc().toInt()

        var fromOwner = false
        var fromPeer = false
        var wasRevived = false

        val peer = MessagesController.getInstance(accountId).getInputPeer(peerUserId)
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        reqLoop@ while (true) {
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_date = endLocalEpoch
                limit = HISTORY_BLOCK_SIZE
            }

            val res = requestHistory(accountId, peerUserId, connectionsManager, req)

            if (res.messages.isEmpty())
                break@reqLoop // break loop if no messages found (probably start of the chat?)

            for (message in res.messages) {
                val message = message as? TLRPC.Message ?: continue

                if (message.date !in startLocalEpoch..endLocalEpoch)
                    break@reqLoop // no more messages for today

                if (message.out)
                    fromOwner = true
                else
                    fromPeer = true

                if (message.message == ServiceMessage.RESTORE_TEXT)
                    wasRevived = true

                endLocalEpoch = message.date

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
