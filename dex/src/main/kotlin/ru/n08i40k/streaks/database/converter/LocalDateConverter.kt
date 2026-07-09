package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import kotlinx.datetime.LocalDate

class LocalDateConverter {
    @TypeConverter
    fun fromLocalDate(date: LocalDate): Long = date.toEpochDays()

    @TypeConverter
    fun toLocalDate(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay)
}
