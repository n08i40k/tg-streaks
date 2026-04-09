package ru.n08i40k.streaks.extension

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun LocalDate.prev(): LocalDate = this.minusDays(1)
fun LocalDate.next(): LocalDate = this.plusDays(1)

fun LocalDate.toEpochSecondUtc(): Long = this.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
fun LocalDate.toEpochSecondSystem(): Long =
    this.atStartOfDay(ZoneOffset.systemDefault()).toEpochSecond()

fun LocalDate.fmt(): String =
    this.format(DateTimeFormatter.ISO_DATE)