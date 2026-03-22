package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import ru.n08i40k.streaks.data.StreakPetTaskType

class StreakPetTaskTypeConverter {
    @TypeConverter
    fun fromTaskType(type: StreakPetTaskType?): String? = type?.name

    @TypeConverter
    fun toTaskType(value: String?): StreakPetTaskType? = value?.let(StreakPetTaskType::valueOf)
}
