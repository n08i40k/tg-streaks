package ru.n08i40k.streaks.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

sealed class RequestOutcome {
    data class Success(val response: TLObject) : RequestOutcome() {
        inline fun <reified T : TLObject> cast(): T =
            this.response as? T
                ?: throw ClassCastException("Failed to cast ${this.response.javaClass.name} to ${T::class.java.name}")
    }

    data class Failure(val error: TLRPC.TL_error) : RequestOutcome()
    data class RateLimit(val retryDelay: Long) : RequestOutcome()
    data class TransientFailure(val error: TLRPC.TL_error, val retryDelay: Long) : RequestOutcome()
    data object TimeOut : RequestOutcome()
}

suspend fun ConnectionsManager.sendRequestBlocking(
    req: TLObject,
    timeout: Long = 5000,
    minRetryDelay: Long = 0
): RequestOutcome {
    val deferred = CompletableDeferred<RequestOutcome>()

    val requestId = this.sendRequest(
        req,
        { response, error ->
            deferred.complete(
                when {
                    response != null ->
                        RequestOutcome.Success(response)

                    error != null ->
                        if (error.isRateLimited())
                            RequestOutcome.RateLimit(error.retryDelayMs(minRetryDelay))
                        else if (error.isTransientFailure())
                            RequestOutcome.TransientFailure(
                                error,
                                maxOf(minRetryDelay, 5_000L)
                            )
                        else
                            RequestOutcome.Failure(error)

                    else ->
                        throw IllegalStateException("Telegram returned nether response nor error")
                }
            )
        }, 2 or 64 or 1024
    )

    return withTimeoutOrNull(timeout) { deferred.await() } ?: run {
        this.cancelRequest(requestId, true)
        return@run RequestOutcome.TimeOut
    }
}
