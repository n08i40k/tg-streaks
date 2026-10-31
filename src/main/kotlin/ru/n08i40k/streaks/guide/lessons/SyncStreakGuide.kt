package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.streaks.guide.presentation.StreakGuideCommand
import ru.n08i40k.streaks.guide.presentation.StreakGuideMessage
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.guide.presentation.StreakGuideTargets
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Duration.Companion.seconds

private const val LENGTH = 12

internal fun syncStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val history = conversationHistory(days = LENGTH, lastDayAt = now - 2 * REPLY_DELAY_SECONDS)
    val offer = GuideMessage(
        history.size + 1,
        Strings.service_sync_offer_self_text(),
        outgoing = true,
        date = now - REPLY_DELAY_SECONDS,
        content = StreakGuideMessage.SYNC_OFFER,
    )
    val applied = GuideMessage(
        history.size + 2,
        Strings.service_sync_applied_self_text(),
        outgoing = false,
        date = now,
        content = StreakGuideMessage.SYNC_APPLIED,
    )

    return guideScript(Strings.guide_sync_title()) {
        step(Strings.guide_sync_1_title(), Strings.guide_sync_1_text()) {
            openFromChatList(history, badge = flame(LENGTH))
        }

        controlPanelSteps()

        step(Strings.guide_sync_2_title(), Strings.guide_sync_2_text()) {
            +TapTarget(StreakGuideTargets.CONTROL_SYNC_PLUGIN)
            +Extension(StreakGuideCommand.PeerHasPlugin)
        }

        step(Strings.guide_sync_3_title(), Strings.guide_sync_3_text()) {
            +TapTarget(StreakGuideTargets.CONTROL_SYNC_OFFER)
            +OpenPanel(StreakGuidePanels.CONFIRM_SYNC_OFFER)
        }

        step(Strings.guide_sync_4_title(), Strings.guide_sync_4_text()) {
            +TapTarget(StreakGuideTargets.CONFIRM_ACCEPT)
            +Back
            +Back
            +AppendMessage(offer)
        }

        step(Strings.guide_sync_5_title(), Strings.guide_sync_5_text()) {
            +Wait(1.seconds)
            +AppendMessage(applied)
        }

        step(Strings.guide_sync_6_title(), Strings.guide_sync_6_text()) {
            returnToConversationList()
        }
    }
}
