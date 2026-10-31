package ru.n08i40k.guides.player

import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.n08i40k.guides.api.GuideBadge
import ru.n08i40k.guides.api.GuideBadgeSession
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.GuideStep
import ru.n08i40k.guides.profile.GuidePeer
import ru.n08i40k.guides.profile.GuideProfiles
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.guides.scene.chat.ChatActivityGuideScene
import ru.n08i40k.guides.scene.list.ChatListGuideScene
import ru.n08i40k.guides.ui.GuideLabels
import ru.n08i40k.guides.util.GuideCleanup

internal class GuidePlayer(
    private val script: GuideScript,
    private val preview: FrameLayout,
    private val profiles: GuideProfiles,
    private val presentation: GuidePresentation?,
    private val labels: GuideLabels,
    private val onStep: (step: GuideStep, index: Int, count: Int) -> Unit,
    private val onBusy: (Boolean) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val host = GuideSceneHost(preview)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val chats = mutableMapOf<Long, GuideChat>()
    private var openedChat: GuideChat? = null
    private var badges: GuideBadgeSession? = null
    private var prepared = false
    private var index = -1
    private var busy = false
    private var disposed = false

    fun start() {
        if (index == -1)
            next()
    }

    fun next() {
        if (disposed || busy)
            return

        if (index == script.steps.lastIndex)
            return finish()

        index++
        busy = true
        onBusy(true)

        val step = script.steps[index]

        scope.launch {
            try {
                if (!prepared)
                    prepare()

                onStep(step, index, script.steps.size)

                for (action in step.actions)
                    perform(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            } finally {
                busy = false

                if (!disposed)
                    onBusy(false)
            }
        }
    }

    fun updateTheme() =
        host.updateTheme()

    fun dispose() {
        if (disposed)
            return

        disposed = true

        val session = badges

        badges = null
        GuideCleanup.run({ scope.cancel() }, { host.dispose() }, { session?.close() })
    }

    private suspend fun prepare() {
        onStep(GuideStep(labels.loadingTitle, labels.loadingDescription, emptyList()), index, script.steps.size)
        profiles.prepare(script)
        badges = presentation?.openBadges(preview, profiles.account)
        host.setRoot(ChatListGuideScene(preview.context, profiles))
        prepared = true
    }

    private fun finish() {
        try {
            dispose()
        } catch (error: Throwable) {
            return onError(error)
        }

        onFinished()
    }

    private fun fail(error: Throwable) {
        try {
            dispose()
        } catch (cleanup: Throwable) {
            if (cleanup !== error)
                error.addSuppressed(cleanup)
        }

        onError(error)
    }

    private suspend fun perform(action: GuideAction) {
        when (action) {
            is GuideAction.Wait -> delay(action.duration)

            is GuideAction.PrefetchProfiles -> profiles.prefetch(action.profiles)

            is GuideAction.ShowChats -> {
                chats.clear()
                chats.putAll(action.chats.associateBy { it.id })
                openedChat = null
                updateBadges()
                host.backToRoot { it.perform(action) }
            }

            is GuideAction.UpdateChat -> {
                chats[action.chat.id] = action.chat
                updateBadges()
                host.root.perform(action)
            }

            is GuideAction.SetBadge -> {
                chats[action.id] = chat(action.id).copy(badge = action.badge)
                updateBadges()
                host.root.perform(action)
            }

            is GuideAction.OpenChat -> {
                val chat = chat(action.id)

                host.open(
                    ChatActivityGuideScene(
                        preview.context,
                        peer(chat).dialogId,
                        action.messages,
                        profiles.account,
                        presentation
                    )
                )
                openedChat = chat
            }

            GuideAction.ReturnToChats -> {
                host.backToRoot()
                openedChat = null
            }

            is GuideAction.OpenPanel -> {
                val chat = checkNotNull(openedChat) { "Panels open over a chat" }

                (host.current as? ChatActivityGuideScene)?.closeMenu()
                host.open(
                    checkNotNull(presentation) { "OpenPanel needs a GuidePresentation" }
                        .panel(preview.context, action.key, peer(chat).entity, profiles.account)
                )
            }

            GuideAction.Back -> host.back()

            else -> checkNotNull(host.current) { "No guide scene is active" }.perform(action)
        }
    }

    private fun chat(id: Long): GuideChat =
        checkNotNull(chats[id]) { "Unknown guide chat $id" }

    private fun peer(chat: GuideChat): GuidePeer =
        checkNotNull(profiles.peer(chat)) { "Guide chat ${chat.id} has no loaded profile" }

    private fun updateBadges() {
        val session = badges ?: return
        val state = linkedMapOf<Long, GuideBadge?>()

        for (chat in chats.values)
            profiles.peer(chat)?.let { state[it.dialogId] = chat.badge }

        session.update(state)
    }
}
