package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.guides.scene.chat.GuideChatTargets
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Duration.Companion.milliseconds

internal fun maintainStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val history = conversationHistory(days = 3, lastDayAt = now - DAY_SECONDS)
    val sent = GuideMessage(7, REQUEST_TEXT, outgoing = true, date = now - REPLY_DELAY_SECONDS)

    return guideScript(Strings.guide_maintain_title()) {
        step(Strings.guide_maintain_1_title(), Strings.guide_maintain_1_text()) {
            openFromChatList(history, badge = flame(3, cold = true))
        }

        step(Strings.guide_maintain_2_title(), Strings.guide_maintain_2_text()) {
            +SendMessage(sent)
        }

        step(Strings.guide_maintain_3_title(), Strings.guide_maintain_3_text()) {
            +Wait(900.milliseconds)
            +ReceiveMessage(GuideMessage(8, REPLY_TEXT, outgoing = false, date = now))
            +SetBadge(PERSON, flame(4))
            +TapTarget(GuideChatTargets.BADGE)
        }

        step(Strings.guide_maintain_4_title(), Strings.guide_maintain_4_text()) {
            returnToConversationList()
        }
    }
}
