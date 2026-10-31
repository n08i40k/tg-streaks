package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.guides.scene.chat.GuideChatTargets
import ru.n08i40k.guides.scene.profile.ProfileActivityGuideScene
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.i18n.Strings

internal fun showcaseStreakGuide(): GuideScript {
    val now = guideTimestamp()

    return guideScript(Strings.guide_profile_title()) {
        step(Strings.guide_profile_chat_title(), Strings.guide_profile_chat_text()) {
            openFromChatList(conversationHistory(days = 1, lastDayAt = now - REPLY_DELAY_SECONDS), badge = flame(30))
        }

        step(Strings.guide_profile_open_title(), Strings.guide_profile_open_text()) {
            +TapTarget(GuideChatTargets.PROFILE)
            +OpenPanel(StreakGuidePanels.PROFILE)
        }

        step(Strings.guide_profile_badge_title(), Strings.guide_profile_badge_text()) {
            +TapTarget(ProfileActivityGuideScene.BADGE)
            +OpenPanel(StreakGuidePanels.STREAK_INFO)
        }

        step(Strings.guide_profile_done_title(), Strings.guide_profile_done_text()) {
            +Back
            +Back
            +SetBadge(PERSON, flame(30))
            returnToConversationList()
        }
    }
}
