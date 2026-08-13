package ru.n08i40k.streaks.data

import android.graphics.Color
import kotlinx.collections.immutable.toImmutableList

private fun rgb(r: Int, g: Int, b: Int): Color =
    Color.valueOf(r.toFloat() / 255f, g.toFloat() / 255f, b.toFloat() / 255)

enum class StreakLevel(
    val length: Int,
    val color: Color,
    val documentId: Long,
    val popupResourceName: String
) {
    Cold(0, rgb(175, 175, 175), 5285071881815235305, ""),
    Days3(3, rgb(255, 154, 0), 5285079178964672780, "3.webm"),
    Days10(10, rgb(255, 100, 0), 5285274844789777412, "10.webm"),
    Days30(30, rgb(255, 61, 0), 5285076623459129616, "30.webm"),
    Days100(100, rgb(255, 0, 200), 5285003347022093599, "100.webm"),
    Days200(200, rgb(176, 0, 255), 5285514817497504375, "200.webm");

    val colorInt: Int = color.toArgb()

    companion object {
        val levels = sortedSetOf(
            compareBy { it.length },
            Cold,
            Days3,
            Days10,
            Days30,
            Days100,
            Days200
        ).toImmutableList()

        fun findNext(level: StreakLevel): StreakLevel? =
            levels.firstOrNull { it.length > level.length }

        fun findFirstVisible(): StreakLevel =
            levels.first { it.length >= Streak.MIN_VISIBLE_LENGTH }

        fun findByLengthApproximate(length: Int): StreakLevel =
            levels.findLast { it.length <= length } ?: levels.last()
    }
}
