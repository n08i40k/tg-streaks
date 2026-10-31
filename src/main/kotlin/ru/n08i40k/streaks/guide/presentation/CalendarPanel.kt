package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.guides.scene.GuideFragmentScene
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.extension.toInstant
import ru.n08i40k.streaks.override.FixupCalendarActivity
import kotlin.time.Duration.Companion.hours

internal fun calendarPanel(context: Context, user: TLRPC.User, account: Int): GuideScene {
    val adapter = LocalCalendarAdapter(UserConfig.getInstance(account).clientUserId, user.id)
    val fragment = FixupCalendarActivity.create(user.id, adapter).apply { setCurrentAccount(account) }

    fun missedDay() =
        checkNotNull(adapter.shown) { "Show the calendar data first" }.start.minus(1, DateTimeUnit.DAY)

    fun dayView(day: LocalDate) =
        checkNotNull(fragment.findDayView(day)) { "$day is not visible in the calendar" }

    return GuideFragmentScene(
        context,
        fragment,
        target = { target ->
            check(target == StreakGuideTargets.CALENDAR_MISSED_DAY) { "Unknown calendar target: $target" }
            dayView(missedDay()).first
        },
        targetPoint = { _, _ -> dayView(missedDay()).second },
        command = { extension ->
            val command = extension as? StreakGuideCommand.Calendar
                ?: error("The calendar does not handle $extension")

            adapter.shown = command
            fragment.reloadSnapshot()
        },
        styleActionBar = false,
    )
}

private class LocalCalendarAdapter(
    private val ownerUserId: Long,
    private val peerUserId: Long,
) : FixupCalendarActivity.Adapter {
    var shown: StreakGuideCommand.Calendar? = null

    override val loadsMediaPreviews = false

    override suspend fun getSnapshot(): StreaksController.CalendarInteractionSnapshot {
        val timeZone = TimeZone.currentSystemDefault()
        val data = shown
        val streak = data?.let {
            val lastActivity = it.end.toInstant(timeZone) + 12.hours

            Streak(ownerUserId, peerUserId, it.start.toInstant(timeZone), lastActivity, lastActivity, 0, timeZone = timeZone)
        }

        return StreaksController.CalendarInteractionSnapshot(
            timeZone = timeZone,
            streak = streak,
            restoreDays = data?.restoreDays.orEmpty(),
            manualRestoresUsed = data?.restoreDays?.size ?: 0,
        )
    }

    override suspend fun analyzeTap(day: LocalDate) =
        StreaksController.CalendarTapDecision.Ignore

    override suspend fun addManualRestore(day: LocalDate) =
        StreaksController.AddManualCalendarRestoreResult.AlreadyExists

    override fun rebuild(onComplete: () -> Unit) = Unit
}
