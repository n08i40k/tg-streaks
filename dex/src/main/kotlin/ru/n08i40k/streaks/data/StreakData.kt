package ru.n08i40k.streaks.data

import android.graphics.Color

data class StreakData(
    val length: Int,
    val documentId: Long,
    val accentColor: Color,
    val isJubilee: Boolean
) {
    companion object {
        fun fromArray(array: Array<Any>): StreakData = StreakData(
            array[0] as Int,
            array[1] as Long,
            array[2] as Color,
            array[3] as Boolean
        )
    }
}
