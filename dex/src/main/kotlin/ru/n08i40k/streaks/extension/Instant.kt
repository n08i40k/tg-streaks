package ru.n08i40k.streaks.extension

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun Instant.toLocalDateTime(): LocalDateTime =
    this.toLocalDateTime(TimeZone.currentSystemDefault())

fun Instant.toLocalDate(): LocalDate =
    this.toLocalDateTime().date