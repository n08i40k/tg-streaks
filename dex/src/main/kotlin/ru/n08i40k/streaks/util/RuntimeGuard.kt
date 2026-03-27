package ru.n08i40k.streaks.util

import kotlinx.coroutines.delay
import org.telegram.messenger.ApplicationLoader
import org.telegram.tgnet.ConnectionsManager

object RuntimeGuard {
    private lateinit var logger: Logger
    private const val POLL_DELAY_MS = 1_000L

    fun isAppForeground(): Boolean =
        !ApplicationLoader.mainInterfacePaused && !ApplicationLoader.mainInterfaceStopped

    fun isTelegramConnectionStable(accountId: Int): Boolean {
        val state = ConnectionsManager.getInstance(accountId).connectionState
        return state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating
    }

    suspend fun awaitAppForeground(reason: String) {
        var loggedWait = false

        while (!isAppForeground()) {
            if (!loggedWait) {
                logger.info("[RuntimeGuard] Paused $reason until the app returns to foreground")
                loggedWait = true
            }

            delay(POLL_DELAY_MS)
        }

        if (loggedWait)
            logger.info("[RuntimeGuard] Resumed $reason after the app returned to foreground")
    }

    suspend fun awaitAppForegroundAndConnection(
        accountId: Int,
        reason: String,
    ) {
        awaitAppForeground(reason)

        var loggedWait = false

        while (true) {
            awaitAppForeground(reason)

            val state = ConnectionsManager.getInstance(accountId).connectionState
            if (isTelegramConnectionStable(accountId)) {
                if (loggedWait) {
                    logger.info("[RuntimeGuard] Resumed $reason after Telegram connection recovered")
                }

                return
            }

            if (!loggedWait) {
                logger.info(
                    "[RuntimeGuard] Paused $reason until Telegram connection becomes stable " +
                            "(account=$accountId, state=${connectionStateLabel(state)})"
                )
                loggedWait = true
            }

            delay(POLL_DELAY_MS)
        }
    }

    suspend fun pauseAwareDelay(
        delayMs: Long,
        reason: String,
    ) {
        var remainingMs = delayMs.coerceAtLeast(0L)

        while (remainingMs > 0L) {
            awaitAppForeground(reason)

            val chunkMs = minOf(remainingMs, POLL_DELAY_MS)
            delay(chunkMs)
            remainingMs -= chunkMs
        }
    }

    private fun connectionStateLabel(state: Int): String =
        when (state) {
            ConnectionsManager.ConnectionStateConnected -> "connected"
            ConnectionsManager.ConnectionStateConnecting -> "connecting"
            ConnectionsManager.ConnectionStateConnectingToProxy -> "connecting_to_proxy"
            ConnectionsManager.ConnectionStateUpdating -> "updating"
            ConnectionsManager.ConnectionStateWaitingForNetwork -> "waiting_for_network"
            else -> "unknown($state)"
        }

    fun setLogger(logger: Logger) {
        this.logger = logger
    }
}
