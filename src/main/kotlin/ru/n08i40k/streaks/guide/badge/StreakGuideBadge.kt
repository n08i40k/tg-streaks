package ru.n08i40k.streaks.guide.badge

import ru.n08i40k.guides.api.GuideBadge

internal data class StreakGuideBadge(val length: Int, val cold: Boolean = false) : GuideBadge
