package ru.n08i40k.guides.profile

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.guides.api.GuideLogger
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.model.GuideProfile
import ru.n08i40k.guides.model.GuideScript
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

class GuideProfiles(
    val account: Int = UserConfig.selectedAccount,
    private val logger: GuideLogger = GuideLogger.DEFAULT,
) {
    private val peers = mutableMapOf<String, GuidePeer>()

    private val controller
        get() = MessagesController.getInstance(account)

    suspend fun prefetch(profiles: List<GuideProfile>) {
        for (profile in profiles.distinctBy(::key))
            resolve(profile)
    }

    internal suspend fun prepare(script: GuideScript) =
        prefetch(
            script.steps.flatMap { it.actions }.flatMap { action ->
                when (action) {
                    is GuideAction.ShowChats -> action.chats.mapNotNull { it.profile }
                    is GuideAction.UpdateChat -> listOfNotNull(action.chat.profile)
                    is GuideAction.PrefetchProfiles -> action.profiles
                    else -> emptyList()
                }
            }
        )

    internal fun peer(chat: GuideChat): GuidePeer? =
        chat.profile?.let { peers[key(it)] }

    private fun key(profile: GuideProfile): String =
        when (profile) {
            is GuideProfile.UserId -> "id:${profile.id}"
            is GuideProfile.Username -> "name:${profile.normalized}"
        }

    private suspend fun resolve(profile: GuideProfile): GuidePeer {
        peers[key(profile)]?.let { return it }

        val peer = when (profile) {
            is GuideProfile.UserId -> {
                val user = controller.getUser(profile.id)?.takeIf { !it.min && (it.access_hash != 0L || it.self) }
                    ?: throw GuideProfileLoadException(
                        "user ${profile.id}",
                        "unavailable in this account; use GuideProfile.Username"
                    )

                GuidePeer(user, profile.id)
            }

            is GuideProfile.Username -> resolveUsername(profile.normalized)
        }

        peers[key(profile)] = peer

        if (peer.dialogId > 0)
            peers[key(GuideProfile.UserId(peer.dialogId))] = peer

        return peer
    }

    private suspend fun resolveUsername(username: String): GuidePeer {
        val name = "@$username"

        logger.info("GuideProfiles: resolving $name on account=$account")

        return withTimeoutOrNull(RESOLVE_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val connections = ConnectionsManager.getInstance(account)
                val request = TLRPC.TL_contacts_resolveUsername().apply { this.username = username }
                val token = connections.sendRequest(
                    request,
                    { response, error ->

                        val resolved = response as? TLRPC.TL_contacts_resolvedPeer
                        val peer = resolved?.peer
                        val entity: TLObject? = when {
                            peer == null -> null
                            peer.user_id != 0L -> resolved.users.firstOrNull { it.id == peer.user_id }
                            peer.channel_id != 0L -> resolved.chats.firstOrNull { it.id == peer.channel_id }
                            peer.chat_id != 0L -> resolved.chats.firstOrNull { it.id == peer.chat_id }
                            else -> null
                        }
                        val dialogId = when (entity) {
                            is TLRPC.User -> entity.id
                            is TLRPC.Chat -> -entity.id
                            else -> 0L
                        }
                        val complete = when (entity) {
                            is TLRPC.User -> !entity.min
                            is TLRPC.Chat -> !entity.min
                            else -> false
                        }

                        logger.info(
                            "GuideProfiles: response $name account=$account " +
                                "type=${response?.javaClass?.simpleName} peer=${peer?.javaClass?.simpleName} " +
                                "dialogId=$dialogId error=${error?.code}:${error?.text}"
                        )

                        AndroidUtilities.runOnUIThread {
                            if (!continuation.isActive)
                                return@runOnUIThread

                            val failure = when {
                                error != null -> "${error.text} (${error.code})"
                                entity == null || dialogId == 0L -> "Telegram returned no profile"
                                !complete -> "Telegram returned an incomplete profile"
                                else -> null
                            }

                            if (failure != null)
                                return@runOnUIThread continuation.resumeWithException(GuideProfileLoadException(name, failure))

                            controller.putUsers(resolved!!.users, false)
                            controller.putChats(resolved.chats, false)
                            continuation.resume(GuidePeer(entity!!, dialogId))
                        }
                    },
                    ConnectionsManager.RequestFlagFailOnServerErrors or ConnectionsManager.RequestFlagDoNotWaitFloodWait
                )

                logger.info("GuideProfiles: contacts.resolveUsername $name sent token=$token account=$account")
                continuation.invokeOnCancellation {
                    logger.info("GuideProfiles: cancelling $name token=$token account=$account")
                    connections.cancelRequest(token, true)
                }
            }
        } ?: throw GuideProfileLoadException(name, "no response in $RESOLVE_TIMEOUT")
    }

    private companion object {
        val RESOLVE_TIMEOUT = 20.seconds
    }
}
