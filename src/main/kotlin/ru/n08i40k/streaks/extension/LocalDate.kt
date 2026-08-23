package ru.n08i40k.streaks.extension

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

fun LocalDate.Companion.now(timeZone: TimeZone): LocalDate = Clock.System.todayIn(timeZone)

fun LocalDate.toInstant(timeZone: TimeZone): Instant = this.atStartOfDayIn(timeZone)

fun LocalDate.prev(): LocalDate = this.minus(1, DateTimeUnit.DAY)
fun LocalDate.next(): LocalDate = this.plus(1, DateTimeUnit.DAY)

fun LocalDate.minusDays(days: Long): LocalDate = this.minus(days, DateTimeUnit.DAY)
fun LocalDate.plusDays(days: Long): LocalDate = this.plus(days, DateTimeUnit.DAY)

fun LocalDate.toEpochSeconds(timeZone: TimeZone): Long =
    this.atStartOfDayIn(timeZone).epochSeconds

fun LocalDate.fmt(): String = this.toString()

fun LocalDate.diff(other: LocalDate) =
    this.toEpochDays() - other.toEpochDays()