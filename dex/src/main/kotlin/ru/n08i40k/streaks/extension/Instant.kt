package ru.n08i40k.streaks.extension

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun Instant.toLocalDate(timeZone: TimeZone): LocalDate =
    this.toLocalDateTime(timeZone).date

fun Instant.toEpochDays(timeZone: TimeZone): Long =
    this.toLocalDate(timeZone).toEpochDays()