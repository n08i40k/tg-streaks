package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import ru.n08i40k.streaks.extension.rawOffset

class TimeZoneConverter {
    @TypeConverter
    fun fromTimeZone(timeZone: TimeZone): Int = timeZone.rawOffset

    @TypeConverter
    fun toTimeZone(rawOffset: Int): TimeZone = UtcOffset(seconds = rawOffset / 1000).asTimeZone()
}
