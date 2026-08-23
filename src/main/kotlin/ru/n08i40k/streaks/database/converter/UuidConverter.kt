package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import java.util.UUID

class UuidConverter {
    @TypeConverter
    fun fromUuid(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun toUuid(value: String?): UUID? = value?.let { UUID.fromString(it) }
}
