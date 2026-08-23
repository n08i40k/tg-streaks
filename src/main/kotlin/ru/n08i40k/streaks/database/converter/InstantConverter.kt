package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import kotlin.time.Instant

class InstantConverter {
    @TypeConverter
    fun fromInstant(instant: Instant): Long = instant.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMs: Long): Instant = Instant.fromEpochMilliseconds(epochMs)
}
