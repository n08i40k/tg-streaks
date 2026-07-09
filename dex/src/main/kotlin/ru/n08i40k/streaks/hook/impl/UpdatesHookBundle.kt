package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.BaseController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.extension.now
import ru.n08i40k.streaks.extension.toLocalDate
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.TLCompat
import ru.n08i40k.streaks.util.getFieldValue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class UpdatesHookBundle : HookBundle() {
    private data class PendingIncomingUpdate(
        val peerUserId: Long,
        val at: LocalDate,
        val out: Boolean,
        val messageId: Int,
        val message: String?
    )

    private fun extractUpdates(
        accountId: Int,
        updates: TLRPC.Updates
    ): List<PendingIncomingUpdate> {
        fun resolvePrivatePeerUserId(message: TLRPC.Message): Long? {
            val peerUserId = message.peer_id?.user_id?.takeIf { it > 0L }
            val fromUserId = message.from_id?.user_id?.takeIf { it > 0L }
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            return when {
                message.out -> peerUserId
                fromUserId != null && fromUserId != ownerUserId -> fromUserId
                peerUserId != null && peerUserId != ownerUserId -> peerUserId
                else -> null
            }
        }

        return when (updates) {
            is TLRPC.TL_updateShortMessage -> {
                listOf(
                    PendingIncomingUpdate(
                        updates.user_id,
                        Instant.fromEpochSeconds(updates.date.toLong()).toLocalDate(),
                        updates.out,
                        updates.id,
                        updates.message
                    )
                )
            }

            is TLRPC.TL_updates -> {
                updates.updates.mapNotNull {
                    if (it.javaClass.name != TLCompat.TL_updateNewMessage.CLASS_NAME)
                        return@mapNotNull null

                    val message = TLCompat.TL_updateNewMessage(it).message
                    val peerUserId = resolvePrivatePeerUserId(message) ?: return@mapNotNull null

                    PendingIncomingUpdate(
                        peerUserId,
                        Instant.fromEpochSeconds(message.date.toLong()).toLocalDate(),
                        message.out,
                        message.id,
                        message.message
                    )
                }
            }

            is TLRPC.TL_updatesCombined -> {
                updates.updates.mapNotNull {
                    if (it.javaClass.name != TLCompat.TL_updateNewMessage.CLASS_NAME)
                        return@mapNotNull null

                    val message = TLCompat.TL_updateNewMessage(it).message
                    val peerUserId = resolvePrivatePeerUserId(message) ?: return@mapNotNull null

                    PendingIncomingUpdate(
                        peerUserId,
                        Instant.fromEpochSeconds(message.date.toLong()).toLocalDate(),
                        message.out,
                        message.id,
                        message.message
                    )
                }
            }

            else -> emptyList()
        }
    }

    private fun handleUpdates(accountId: Int, entries: List<PendingIncomingUpdate>) =
        Plugin.getInstance().apply {
            if (accountId != UserConfig.selectedAccount || entries.isEmpty())
                return@apply

            AccountTaskExecutor.enqueue(accountId, "handle updates for $accountId") {
                for ((peerUserId, at, out, messageId, message) in entries) {
                    streaksController.handleUpdate(
                        accountId,
                        peerUserId,
                        at,
                        out,
                        message
                    )

                    streakPetsController.handleUpdate(
                        accountId,
                        peerUserId,
                        at,
                        messageId,
                        message,
                        out
                    )

                    petUiManager.refreshFabForOpenChat()
                }
            }
        }

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Обработка входящих сообщений
        before(
            MessagesController::class.java.getDeclaredMethod(
                "processUpdates",
                TLRPC.Updates::class.java,
                Boolean::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as BaseController
            val thisClass = BaseController::class.java

            val accountId = getFieldValue<Int>(thisClass, thisObject, "currentAccount")
                ?: return@before

            if (accountId != UserConfig.selectedAccount)
                return@before

            val updates = param.args[0] as TLRPC.Updates
            val entries = extractUpdates(accountId, updates)

            handleUpdates(accountId, entries)
        }

        // Обработка исходящих сообщений
        before(
            SendMessagesHelper::class.java.getDeclaredMethod(
                "sendMessage",
                SendMessagesHelper.SendMessageParams::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as BaseController
            val thisClass = BaseController::class.java

            val sendMessageParams = param.args[0] as SendMessagesHelper.SendMessageParams
            val accountId = getFieldValue<Int>(thisClass, thisObject, "currentAccount")
                ?: UserConfig.selectedAccount

            // TODO: fill message id
            val update = PendingIncomingUpdate(
                sendMessageParams.peer,
                LocalDate.now(),
                true,
                0,
                sendMessageParams.message
            )

            handleUpdates(accountId, listOf(update))
        }
    }
}
