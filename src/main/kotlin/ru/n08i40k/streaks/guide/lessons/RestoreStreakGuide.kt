package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.guides.scene.chat.GuideChatTargets
import ru.n08i40k.streaks.guide.presentation.StreakGuideMessage
import ru.n08i40k.streaks.i18n.Strings

internal fun restoreStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val history = conversationHistory(days = 7, lastDayAt = now - 2 * DAY_SECONDS)

    val endedCard = GuideMessage(
        15,
        Strings.service_streak_ended_title(),
        outgoing = false,
        date = now - REPLY_DELAY_SECONDS,
        content = StreakGuideMessage.ENDED,
    )
    val restoredNotice = GuideMessage(
        16,
        Strings.service_streak_restored_self(),
        outgoing = true,
        date = now,
        content = StreakGuideMessage.RESTORED,
    )

    return guideScript(Strings.guide_restore_title()) {
        step(Strings.guide_restore_1_title(), Strings.guide_restore_1_text()) {
            openFromChatList(history, badge = flame(7, cold = true))
        }

        step(Strings.guide_restore_2_title(), Strings.guide_restore_2_text()) {
            +SetBadge(PERSON, null)
            +AppendMessage(endedCard)
        }

        step(Strings.guide_restore_3_title(), Strings.guide_restore_3_text()) {
            +TapMessage(endedCard.id, actionButton = true)
            +AppendMessage(restoredNotice)
            +SetBadge(PERSON, flame(7))
        }

        step(Strings.guide_restore_4_title(), Strings.guide_restore_4_text()) {
            +TapTarget(GuideChatTargets.BADGE)
        }

        step(Strings.guide_restore_5_title(), Strings.guide_restore_5_text()) {
            returnToConversationList()
        }
    }
}
