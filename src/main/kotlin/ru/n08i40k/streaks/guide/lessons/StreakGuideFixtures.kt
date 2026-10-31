package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.Extension
import ru.n08i40k.guides.model.GuideAction.HighlightChat
import ru.n08i40k.guides.model.GuideAction.OpenChat
import ru.n08i40k.guides.model.GuideAction.OpenChatMenu
import ru.n08i40k.guides.model.GuideAction.OpenPanel
import ru.n08i40k.guides.model.GuideAction.OpenPluginsMenu
import ru.n08i40k.guides.model.GuideAction.ReceiveMessage
import ru.n08i40k.guides.model.GuideAction.ReturnToChats
import ru.n08i40k.guides.model.GuideAction.ScrollToChat
import ru.n08i40k.guides.model.GuideAction.SendMessage
import ru.n08i40k.guides.model.GuideAction.ShowChats
import ru.n08i40k.guides.model.GuideAction.TapChat
import ru.n08i40k.guides.model.GuideAction.TapMenuItem
import ru.n08i40k.guides.model.GuideAction.TapTarget
import ru.n08i40k.guides.model.GuideAction.Wait
import ru.n08i40k.guides.api.GuideBadge
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideProfile
import ru.n08i40k.guides.model.GuideScriptBuilder
import ru.n08i40k.guides.model.GuideStepBuilder
import ru.n08i40k.guides.scene.chat.GuideChatTargets
import ru.n08i40k.streaks.guide.badge.StreakGuideBadge
import ru.n08i40k.streaks.guide.presentation.StreakGuideCommand
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val PERSON = 101L
internal const val REQUEST_TEXT = "выдай галочку етг пж"
internal const val REPLY_TEXT = "ожидайте"
internal const val DAY_SECONDS = 86_400
internal const val REPLY_DELAY_SECONDS = 60

private val CHAT_LIST_PAUSE = 700.milliseconds

internal fun guideTimestamp(): Int = (System.currentTimeMillis() / 1000).toInt()

internal fun flame(length: Int, cold: Boolean = false) = StreakGuideBadge(length, cold)

internal data class ConversationDay(val sent: GuideMessage, val reply: GuideMessage)

internal fun conversationDay(firstId: Int, at: Int) =
    ConversationDay(
        sent = GuideMessage(firstId, REQUEST_TEXT, outgoing = true, date = at - REPLY_DELAY_SECONDS),
        reply = GuideMessage(firstId + 1, REPLY_TEXT, outgoing = false, date = at),
    )

internal fun conversationHistory(days: Int, lastDayAt: Int, skipDaysAgo: Set<Int> = emptySet()): List<GuideMessage> =
    (days - 1 downTo 0)
        .filter { it !in skipDaysAgo }
        .flatMapIndexed { index, daysAgo ->
            val exchange = conversationDay(firstId = index * 2 + 1, at = lastDayAt - daysAgo * DAY_SECONDS)

            listOf(exchange.sent, exchange.reply)
        }

internal fun GuideStepBuilder.exchange(day: ConversationDay) {
    +SendMessage(day.sent)
    +Wait(1.seconds)
    +ReceiveMessage(day.reply)
}

private fun chats(badge: GuideBadge?, awaitingReply: Boolean) =
    listOf(
        GuideChat(
            PERSON,
            "",
            if (awaitingReply) REQUEST_TEXT else REPLY_TEXT,
            profile = GuideProfile.Username("exteraOwner"),
            outgoing = awaitingReply,
            badge = badge,
        ),
        GuideChat(
            102,
            "",
            "когда обнова custom profile",
            profile = GuideProfile.Username("nothalk"),
            outgoing = true,
        ),
        GuideChat(
            103,
            "",
            "чем больше вы мяукаете, тем больше мой п...",
            profile = GuideProfile.Username("pixwet"),
            outgoing = false,
            unreadCount = 1,
        ),
        GuideChat(
            104,
            "",
            "нет иди нахуй",
            profile = GuideProfile.Username("monk"),
        ),
        GuideChat(
            105,
            "",
            "❌ Заявка отклонена",
            profile = GuideProfile.Username("exteraPluginsRobot"),
            unreadCount = 1,
        ),
    )

internal fun GuideStepBuilder.openFromChatList(
    history: List<GuideMessage>,
    badge: GuideBadge? = null,
    awaitingReply: Boolean = false,
) {
    +ShowChats(chats(badge, awaitingReply))
    +ScrollToChat(PERSON)
    +HighlightChat(PERSON)
    +Wait(CHAT_LIST_PAUSE)
    openConversation(history)
}

internal fun GuideStepBuilder.openConversation(history: List<GuideMessage>) {
    +HighlightChat(null)
    +TapChat(PERSON)
    +OpenChat(PERSON, history)
}

internal fun GuideScriptBuilder.pluginsMenuSteps() {
    step(Strings.guide_menu_title(), Strings.guide_menu_text()) {
        +TapTarget(GuideChatTargets.MENU)
        +OpenChatMenu
    }

    step(Strings.guide_plugins_title(), Strings.guide_plugins_text()) {
        +TapTarget(GuideChatTargets.PLUGINS)
        +OpenPluginsMenu
    }
}

internal fun GuideScriptBuilder.controlPanelSteps() {
    pluginsMenuSteps()

    step(Strings.guide_control_title(), Strings.guide_control_text()) {
        +TapMenuItem(Strings.menu_chat_control_panel_title())
        +OpenPanel(StreakGuidePanels.CONTROL)
    }
}

internal fun GuideStepBuilder.rebuildProgress(days: Int) {
    +OpenPanel(StreakGuidePanels.REBUILD)

    for (indexed in listOf(days / 3, days * 2 / 3, days).filter { it > 0 }.distinct()) {
        +Extension(StreakGuideCommand.Indexed(indexed))
        +Wait(450.milliseconds)
    }
}

internal fun GuideStepBuilder.returnToConversationList() {
    +ReturnToChats
    +HighlightChat(PERSON)
}
