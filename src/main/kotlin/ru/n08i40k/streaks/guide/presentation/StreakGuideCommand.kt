package ru.n08i40k.streaks.guide.presentation

import kotlinx.datetime.LocalDate
import ru.n08i40k.guides.api.GuideExtension

internal sealed interface StreakGuideCommand : GuideExtension {
    data class Indexed(val days: Int) : StreakGuideCommand

    data class RebuildResult(val days: Int) : StreakGuideCommand

    data object PeerHasPlugin : StreakGuideCommand

    data object SelectPartnerTimeZone : StreakGuideCommand

    data class Calendar(
        val start: LocalDate,
        val end: LocalDate,
        val restoreDays: Set<LocalDate> = emptySet(),
    ) : StreakGuideCommand
}
