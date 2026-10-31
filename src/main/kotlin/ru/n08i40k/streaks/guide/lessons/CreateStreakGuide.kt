package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.guides.scene.chat.GuideChatTargets
import ru.n08i40k.streaks.guide.presentation.StreakGuideMessage
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.guide.presentation.StreakGuideTargets
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.upgrade.UPGRADE_HINT_DELAY_MS
import ru.n08i40k.streaks.ui.upgrade.UpgradeTimeline
import kotlin.time.Duration.Companion.milliseconds

internal fun createStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val seed = listOf(GuideMessage(90, REQUEST_TEXT, outgoing = true, date = now - 3 * DAY_SECONDS))
    val firstDay = conversationDay(firstId = 1, at = now - 2 * DAY_SECONDS)
    val secondDay = conversationDay(firstId = 3, at = now - DAY_SECONDS)
    val thirdDay = conversationDay(firstId = 5, at = now)

    return guideScript(Strings.guide_create_title()) {
        step(Strings.guide_create_1_title(), Strings.guide_create_1_text()) {
            openFromChatList(seed, awaitingReply = true)
        }

        step(Strings.guide_create_2_title(), Strings.guide_create_2_text()) {
            exchange(firstDay)
        }

        step(Strings.guide_create_3_title(), Strings.guide_create_3_text()) {
            exchange(secondDay)
        }

        step(Strings.guide_create_4_title(), Strings.guide_create_4_text()) {
            exchange(thirdDay)
            +AppendMessage(
                GuideMessage(
                    7,
                    Strings.service_streak_started_text(),
                    outgoing = true,
                    date = now,
                    content = StreakGuideMessage.CREATED,
                )
            )
            +SetBadge(PERSON, flame(3))
            +OpenPanel(StreakGuidePanels.STREAK_CREATED)

            +Wait(UpgradeTimeline.PLAYBACK + UPGRADE_HINT_DELAY_MS.milliseconds + 300.milliseconds)
            +TapTarget(StreakGuideTargets.UPGRADE_DISMISS)
            +Back
            +TapTarget(GuideChatTargets.BADGE)
        }

        step(Strings.guide_create_5_title(), Strings.guide_create_5_text()) {
            returnToConversationList()
        }
    }
}
