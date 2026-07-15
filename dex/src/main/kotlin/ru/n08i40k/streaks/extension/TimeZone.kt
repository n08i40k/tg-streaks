package ru.n08i40k.streaks.extension

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import ru.n08i40k.streaks.i18n.Strings
import kotlin.math.abs
import kotlin.time.Clock

val TimeZone.rawOffset: Int
    get() = offsetAt(Clock.System.now()).totalSeconds * 1000

fun TimeZone.toOffsetString(): String {
    val totalMinutes = rawOffset / 60_000

    val sign = when {
        totalMinutes > 0 -> "+"
        totalMinutes < 0 -> "-"
        else -> " "
    }

    val abs = abs(totalMinutes)

    return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
}

fun TimeZone.toDisplayName(): String {
    val totalMinutes = rawOffset / 60_000

    return when (totalMinutes) {
        0 -> Strings.menu_timezone_select_tz_p0000()

        -30 -> Strings.menu_timezone_select_tz_m0030()
        -60 -> Strings.menu_timezone_select_tz_m0100()
        -90 -> Strings.menu_timezone_select_tz_m0130()
        -120 -> Strings.menu_timezone_select_tz_m0200()
        -150 -> Strings.menu_timezone_select_tz_m0230()
        -180 -> Strings.menu_timezone_select_tz_m0300()
        -210 -> Strings.menu_timezone_select_tz_m0330()
        -240 -> Strings.menu_timezone_select_tz_m0400()
        -270 -> Strings.menu_timezone_select_tz_m0430()
        -300 -> Strings.menu_timezone_select_tz_m0500()
        -330 -> Strings.menu_timezone_select_tz_m0530()
        -360 -> Strings.menu_timezone_select_tz_m0600()
        -390 -> Strings.menu_timezone_select_tz_m0630()
        -420 -> Strings.menu_timezone_select_tz_m0700()
        -450 -> Strings.menu_timezone_select_tz_m0730()
        -480 -> Strings.menu_timezone_select_tz_m0800()

        30 -> Strings.menu_timezone_select_tz_p0030()
        60 -> Strings.menu_timezone_select_tz_p0100()
        90 -> Strings.menu_timezone_select_tz_p0130()
        120 -> Strings.menu_timezone_select_tz_p0200()
        150 -> Strings.menu_timezone_select_tz_p0230()
        180 -> Strings.menu_timezone_select_tz_p0300()
        210 -> Strings.menu_timezone_select_tz_p0330()
        240 -> Strings.menu_timezone_select_tz_p0400()
        270 -> Strings.menu_timezone_select_tz_p0430()
        300 -> Strings.menu_timezone_select_tz_p0500()
        330 -> Strings.menu_timezone_select_tz_p0530()
        360 -> Strings.menu_timezone_select_tz_p0600()
        390 -> Strings.menu_timezone_select_tz_p0630()
        420 -> Strings.menu_timezone_select_tz_p0700()
        450 -> Strings.menu_timezone_select_tz_p0730()
        480 -> Strings.menu_timezone_select_tz_p0800()

        else -> toOffsetString()
    }
}

fun TimeZone.toLocalStartString(): String {
    val totalMinutes = (rawOffset - TimeZone.currentSystemDefault().rawOffset) / 60_000

    val hours = (totalMinutes / 60).let { h -> if (h < 0) 24 + h else h }
    val minutes = abs(totalMinutes) % 60

    return "%02d:%02d".format(hours, minutes)
}
