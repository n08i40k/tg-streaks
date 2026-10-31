package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import ru.n08i40k.guides.scene.GuideFragmentScene
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.streaks.extension.rawOffset
import ru.n08i40k.streaks.ui.StreakControlFragment
import ru.n08i40k.streaks.ui.TimeZoneSelectFragment
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private val SCROLL_PAUSE = 700.milliseconds

internal fun partnerTimeZone(): TimeZone {
    val own = TimeZone.currentSystemDefault().rawOffset
    val hour = 3_600_000

    return TimeZoneSelectFragment.TIME_ZONES
        .filter { abs(it.rawOffset - own) >= hour * 3 / 2 }
        .minBy { abs(it.rawOffset - own - hour * 3) }
}

internal fun controlPanel(context: Context): GuideScene {
    val model = LocalControlModel()
    val fragment = StreakControlFragment(model)

    fun row(target: String) =
        when (target) {
            StreakGuideTargets.CONTROL_TIME_ZONE -> StreakControlFragment.Row.TIME_ZONE_SELECTOR
            StreakGuideTargets.CONTROL_SYNC_PLUGIN -> StreakControlFragment.Row.SYNC_PEER_HAS_PLUGIN_ENABLED_SW
            StreakGuideTargets.CONTROL_SYNC_OFFER -> StreakControlFragment.Row.SYNC_OFFER_BTN
            StreakGuideTargets.CONTROL_REBUILD -> StreakControlFragment.Row.DANGER_ZONE_REBUILD_BOTH_BTN
            else -> error("Unknown control target: $target")
        }

    return GuideFragmentScene(
        context,
        fragment,
        target = { target ->
            checkNotNull(fragment.viewForRow(row(target))) { "Scroll to $target before tapping it" }
        },
        scrollTarget = { target ->
            fragment.smoothScrollToRow(row(target))
            delay(SCROLL_PAUSE)
        },
        command = { extension ->
            when (val command = extension as StreakGuideCommand) {
                StreakGuideCommand.PeerHasPlugin -> model.setPeerHasPluginInstalled(true)
                StreakGuideCommand.SelectPartnerTimeZone -> model.setTimeZone(partnerTimeZone())
                else -> error("The control panel does not handle $command")
            }
        },
        refreshTheme = fragment::refreshEmbeddedTheme,
    )
}

internal fun timeZonePanel(context: Context): GuideScene {
    val fragment = TimeZoneSelectFragment(object : TimeZoneSelectFragment.Adapter {
        override fun getCurrentTimeZone() = TimeZone.currentSystemDefault()

        override fun setCurrentTimeZone(value: TimeZone) = Unit

        override fun hasPet() = false
    })

    return GuideFragmentScene(
        context,
        fragment,
        target = { target ->
            when (target) {
                StreakGuideTargets.TIME_ZONE_PARTNER ->
                    checkNotNull(fragment.viewFor(partnerTimeZone())) { "Scroll to $target before tapping it" }

                StreakGuideTargets.TIME_ZONE_DONE -> fragment.doneButton

                else -> error("Unknown time zone target: $target")
            }
        },
        scrollTarget = { target ->
            check(target == StreakGuideTargets.TIME_ZONE_PARTNER) { "Unknown time zone target: $target" }
            fragment.smoothScrollTo(partnerTimeZone())
            delay(SCROLL_PAUSE)
        },
        command = { extension ->
            check(extension == StreakGuideCommand.SelectPartnerTimeZone) { "The time zone list does not handle $extension" }
            fragment.select(partnerTimeZone())
        },
    )
}

private class LocalControlModel : StreakControlFragment.ViewModel {
    private val state = MutableStateFlow(StreakControlFragment.ViewState())

    override fun state() = state

    override fun setTimeZone(value: TimeZone) {
        state.value = state.value.copy(timeZone = value)
    }

    override fun setPeerHasPluginInstalled(value: Boolean) {
        state.value = state.value.copy(peerHasPluginInstalled = value)
    }

    override fun offerSync() = Unit

    override fun setServiceMessagesCategoryEnabled(categoryName: String, value: Boolean) = Unit

    override fun setPetFabEnabled(value: Boolean) = Unit

    override fun rebuildBoth() = Unit

    override fun rebuildPet() = Unit

    override fun restoreStreak() = Unit

    override fun createPet() = Unit

    override fun goToStreakStart() = Unit

    override fun deleteBoth() = Unit

    override fun deletePet() = Unit
}
