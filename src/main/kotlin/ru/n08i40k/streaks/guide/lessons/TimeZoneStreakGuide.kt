package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.model.GuideAction.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import ru.n08i40k.streaks.guide.presentation.StreakGuideCommand
import ru.n08i40k.streaks.guide.presentation.partnerTimeZone
import ru.n08i40k.streaks.guide.presentation.StreakGuidePanels
import ru.n08i40k.streaks.guide.presentation.StreakGuideTargets
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Instant

private const val COUNTED_LENGTH = 2
private const val REBUILT_LENGTH = 5

private fun partnerEveningsHistory(now: Instant): List<GuideMessage> {
    val zone = partnerTimeZone()
    val evening = LocalTime(23, 0)
    val today = now.toLocalDateTime(zone).date
    val lastDay = today.takeIf { LocalDateTime(it, evening).toInstant(zone) <= now }
        ?: today.minus(1, DateTimeUnit.DAY)

    val replies = (REBUILT_LENGTH - 1 downTo 0).map { daysAgo ->
        val day = lastDay.minus(daysAgo, DateTimeUnit.DAY)
        val time = if (daysAgo == 2) LocalTime(1, 0) else evening

        LocalDateTime(day, time).toInstant(zone).epochSeconds.toInt()
    }

    return replies.flatMapIndexed { index, at ->
        val exchange = conversationDay(firstId = index * 2 + 1, at = at)

        listOf(exchange.sent, exchange.reply)
    }
}

internal fun timeZoneStreakGuide(): GuideScript {
    val history = partnerEveningsHistory(Instant.fromEpochSeconds(guideTimestamp().toLong()))

    return guideScript(Strings.guide_timezone_title()) {
        step(Strings.guide_timezone_1_title(), Strings.guide_timezone_1_text()) {
            openFromChatList(history, badge = flame(COUNTED_LENGTH))
        }

        controlPanelSteps()

        step(Strings.guide_timezone_2_title(), Strings.guide_timezone_2_text()) {
            +TapTarget(StreakGuideTargets.CONTROL_TIME_ZONE)
            +OpenPanel(StreakGuidePanels.TIME_ZONE)
        }

        step(Strings.guide_timezone_3_title(), Strings.guide_timezone_3_text()) {
            +ScrollTarget(StreakGuideTargets.TIME_ZONE_PARTNER)
        }

        step(Strings.guide_timezone_4_title(), Strings.guide_timezone_4_text()) {
            +TapTarget(StreakGuideTargets.TIME_ZONE_PARTNER)
            +Extension(StreakGuideCommand.SelectPartnerTimeZone)
        }

        step(Strings.guide_timezone_5_title(), Strings.guide_timezone_5_text()) {
            +TapTarget(StreakGuideTargets.TIME_ZONE_DONE)
            +OpenPanel(StreakGuidePanels.CONFIRM_TIME_ZONE)
        }

        step(Strings.guide_timezone_6_title(), Strings.guide_timezone_6_text()) {
            +TapTarget(StreakGuideTargets.CONFIRM_ACCEPT)
            +Back
            +Back
            +Extension(StreakGuideCommand.SelectPartnerTimeZone)
            rebuildProgress(REBUILT_LENGTH)
            +Extension(StreakGuideCommand.RebuildResult(REBUILT_LENGTH))
        }

        step(Strings.guide_timezone_7_title(), Strings.guide_timezone_7_text()) {
            +Back
            +Back
            +SetBadge(PERSON, flame(REBUILT_LENGTH))
            returnToConversationList()
        }
    }
}
