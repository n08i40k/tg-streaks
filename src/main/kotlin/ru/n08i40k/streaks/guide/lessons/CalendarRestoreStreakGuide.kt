package ru.n08i40k.streaks.guide.lessons

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.streaks.extension.toInstant
import ru.n08i40k.streaks.extension.toLocalDate
import ru.n08i40k.streaks.guide.presentation.StreakGuideCommand
import ru.n08i40k.streaks.guide.presentation.StreakGuideMessage
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.guide.presentation.StreakGuideTargets
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Instant

private const val BEFORE_GAP = 7
private const val AFTER_GAP = 3
private const val REBUILT_LENGTH = BEFORE_GAP + 1 + AFTER_GAP

internal fun calendarRestoreStreakGuide(): GuideScript {
    val now = guideTimestamp()
    val timeZone = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochSeconds(now.toLong()).toLocalDate(timeZone)
    val currentStart = today.minus(AFTER_GAP - 1, DateTimeUnit.DAY)
    val missedDay = today.minus(AFTER_GAP, DateTimeUnit.DAY)
    val firstDay = today.minus(REBUILT_LENGTH - 1, DateTimeUnit.DAY)

    val conversation = conversationHistory(days = REBUILT_LENGTH, lastDayAt = now, skipDaysAgo = setOf(AFTER_GAP))

    val ended = GuideMessage(
        conversation.size + 1,
        Strings.service_streak_ended_title(),
        outgoing = false,
        date = (currentStart.toInstant(timeZone).epochSeconds + 60).toInt(),
        content = StreakGuideMessage.ENDED,
    )
    val history = (conversation + ended).sortedBy { it.date }

    return guideScript(Strings.guide_calendar_title()) {
        step(Strings.guide_calendar_1_title(), Strings.guide_calendar_1_text()) {
            openFromChatList(history, badge = flame(AFTER_GAP))
        }

        pluginsMenuSteps()

        step(Strings.guide_calendar_2_title(), Strings.guide_calendar_2_text()) {
            +TapMenuItem(Strings.menu_chat_restore_streak_exact_title())
            +OpenPanel(StreakGuidePanels.CALENDAR)
            +Extension(StreakGuideCommand.Calendar(currentStart, today))
        }

        step(Strings.guide_calendar_3_title(), Strings.guide_calendar_3_text()) {
            +TapTarget(StreakGuideTargets.CALENDAR_MISSED_DAY)
            +OpenPanel(StreakGuidePanels.CONFIRM_CALENDAR_RESTORE)
        }

        step(Strings.guide_calendar_4_title(), Strings.guide_calendar_4_text()) {
            +TapTarget(StreakGuideTargets.CONFIRM_ACCEPT)
            +Back
            +Extension(StreakGuideCommand.Calendar(currentStart, today, setOf(missedDay)))
            +OpenPanel(StreakGuidePanels.CONFIRM_CALENDAR_REBUILD)
        }

        step(Strings.guide_calendar_5_title(), Strings.guide_calendar_5_text()) {
            +TapTarget(StreakGuideTargets.CONFIRM_ACCEPT)
            +Back
            rebuildProgress(REBUILT_LENGTH)
            +Extension(StreakGuideCommand.RebuildResult(REBUILT_LENGTH))
        }

        step(Strings.guide_calendar_6_title(), Strings.guide_calendar_6_text()) {
            +Back
            +Extension(StreakGuideCommand.Calendar(firstDay, today, setOf(missedDay)))
        }

        step(Strings.guide_calendar_7_title(), Strings.guide_calendar_7_text()) {
            +Back
            +SetBadge(PERSON, flame(REBUILT_LENGTH))
            returnToConversationList()
        }
    }
}
