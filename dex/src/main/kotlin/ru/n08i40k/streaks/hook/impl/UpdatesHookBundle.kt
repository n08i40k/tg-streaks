@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BaseController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.getFieldValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

        @Suppress("IMPOSSIBLE_IS_CHECK_WARNING", "KotlinConstantConditions")
        return when (updates) {
            is TLRPC.TL_updateShortMessage -> {
                listOf(
                    PendingIncomingUpdate(
                        updates.user_id,
                        Instant.ofEpochSecond(updates.date.toLong())
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate(),
                        updates.out,
                        updates.id,
                        updates.message
                    )
                )
            }

            is TLRPC.TL_updates -> {
                updates.updates.mapNotNull {
                    when (it) {
                        is TLRPC.TL_updateNewMessage -> resolvePrivatePeerUserId(it.message)
                            ?.let { peerUserId ->
                                PendingIncomingUpdate(
                                    peerUserId,
                                    Instant.ofEpochSecond(it.message.date.toLong())
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate(),
                                    it.message.out,
                                    it.message.id,
                                    it.message.message
                                )
                            }

                        else -> null
                    }
                }
            }

            is TLRPC.TL_updatesCombined -> {
                updates.updates.mapNotNull {
                    when (it) {
                        is TLRPC.TL_updateNewMessage -> resolvePrivatePeerUserId(it.message)
                            ?.let { peerUserId ->
                                PendingIncomingUpdate(
                                    peerUserId,
                                    Instant.ofEpochSecond(it.message.date.toLong())
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate(),
                                    it.message.out,
                                    it.message.id,
                                    it.message.message
                                )
                            }

                        else -> null
                    }
                }
            }

            else -> emptyList()
        }
    }

    private fun handleUpdates(accountId: Int, entries: List<PendingIncomingUpdate>) =
        Plugin.getInstance().apply {
            if (accountId != UserConfig.selectedAccount || entries.isEmpty())
                return@apply

            accountTaskRunnerRegistry.enqueue(accountId, "handle updates for $accountId") {
                var changed = false

                for ((peerUserId, at, out, messageId, message) in entries) {
                    val result =
                        streaksController.handleUpdate(accountId, peerUserId, at, out, message)

                    streakPetsController.handleUpdate(
                        accountId,
                        peerUserId,
                        at,
                        messageId,
                        message,
                        out
                    )

                    petUiManager.refreshFabForOpenChat()

                    if (result.changed) {
                        changed = true
                        streaksController.syncUserState(accountId, peerUserId)

                        AndroidUtilities.runOnUIThread {
                            streakEmojiRegistry.refreshByPeerUserId(peerUserId)
                        }
                    }
                }

                if (changed)
                    AndroidUtilities.runOnUIThread { streakEmojiRegistry.refreshDialogCells() }
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
