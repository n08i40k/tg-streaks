package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.streaks.guide.presentation.StreakGuideCommand
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.guide.presentation.StreakGuideTargets
import ru.n08i40k.streaks.i18n.Strings

private const val REBUILT_LENGTH = 10

internal fun rebuildStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val history = conversationHistory(days = REBUILT_LENGTH, lastDayAt = now)

    return guideScript(Strings.guide_rebuild_title()) {
        step(Strings.guide_rebuild_1_title(), Strings.guide_rebuild_1_text()) {
            openFromChatList(history, badge = flame(3))
        }

        controlPanelSteps()

        step(Strings.guide_scroll_title(), Strings.guide_scroll_text()) {
            +ScrollTarget(StreakGuideTargets.CONTROL_REBUILD)
        }

        step(Strings.guide_rebuild_4_title(), Strings.guide_rebuild_4_text()) {
            +TapTarget(StreakGuideTargets.CONTROL_REBUILD)
            +OpenPanel(StreakGuidePanels.CONFIRM_REBUILD)
        }

        step(Strings.guide_rebuild_5_title(), Strings.guide_rebuild_5_text()) {
            +TapTarget(StreakGuideTargets.CONFIRM_ACCEPT)
            +Back
            rebuildProgress(REBUILT_LENGTH)
        }

        step(Strings.guide_rebuild_6_title(), Strings.guide_rebuild_6_text()) {
            +Extension(StreakGuideCommand.RebuildResult(REBUILT_LENGTH))
        }

        step(Strings.guide_rebuild_final_title(), Strings.guide_rebuild_final_text()) {
            +Back
            +Back
            +SetBadge(PERSON, flame(REBUILT_LENGTH))
            returnToConversationList()
        }
    }
}
