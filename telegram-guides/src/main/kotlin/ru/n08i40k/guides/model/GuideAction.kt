package ru.n08i40k.guides.model

import ru.n08i40k.guides.api.GuideBadge
import ru.n08i40k.guides.api.GuideExtension
import kotlin.time.Duration

sealed interface GuideAction {
    data class Wait(val duration: Duration) : GuideAction {
        init {
            require(duration.isFinite() && duration >= Duration.ZERO) { "Wait needs a finite non-negative duration" }
        }
    }

    data class PrefetchProfiles(val profiles: List<GuideProfile>) : GuideAction

    data class SetBadge(val id: Long, val badge: GuideBadge?) : GuideAction

    data class ShowChats(val chats: List<GuideChat>) : GuideAction

    data class UpdateChat(val chat: GuideChat) : GuideAction

    data class HighlightChat(val id: Long?) : GuideAction

    data class ScrollToChat(val id: Long) : GuideAction

    data class TapChat(val id: Long) : GuideAction

    data class TapAt(val x: Float, val y: Float) : GuideAction {
        init {
            require(x in 0f..1f && y in 0f..1f) { "TapAt coordinates must be in 0..1" }
        }
    }

    data class SetTitle(val title: String) : GuideAction

    data class OpenChat(val id: Long, val messages: List<GuideMessage> = emptyList()) : GuideAction {
        init {
            validateGuideMessages(messages)
        }
    }

    data object ReturnToChats : GuideAction

    data class ShowMessages(val messages: List<GuideMessage>) : GuideAction {
        init {
            validateGuideMessages(messages)
        }
    }

    data class SendMessage(val message: GuideMessage) : GuideAction

    data class ReceiveMessage(val message: GuideMessage) : GuideAction

    data class AppendMessage(val message: GuideMessage) : GuideAction

    data class TapMessage(val id: Int, val actionButton: Boolean = false) : GuideAction

    data object OpenChatMenu : GuideAction

    data object OpenPluginsMenu : GuideAction

    data class TapMenuItem(val text: String) : GuideAction

    data class OpenPanel(val key: String) : GuideAction

    data object Back : GuideAction

    data class TapTarget(val target: String) : GuideAction

    data class ScrollTarget(val target: String) : GuideAction

    data class Extension(val command: GuideExtension) : GuideAction
}
