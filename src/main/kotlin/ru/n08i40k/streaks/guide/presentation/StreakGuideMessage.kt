package ru.n08i40k.streaks.guide.presentation

import ru.n08i40k.guides.api.GuideMessageContent
import ru.n08i40k.streaks.constants.ServiceMessage

internal enum class StreakGuideMessage(val marker: String) : GuideMessageContent {
    CREATED(ServiceMessage.CREATE_TEXT),
    ENDED(ServiceMessage.DEATH_TEXT),
    RESTORED(ServiceMessage.RESTORE_TEXT),
    SYNC_OFFER(ServiceMessage.SYNC_OFFER),
    SYNC_APPLIED(ServiceMessage.SYNC_APPLIED),
}
