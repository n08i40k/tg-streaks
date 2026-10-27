package ru.n08i40k.streaks.util

import android.graphics.Color

fun colorBlend(a: Int, b: Int, amount: Float): Int = Color.rgb(
    (Color.red(a) * (1 - amount) + Color.red(b) * amount).toInt(),
    (Color.green(a) * (1 - amount) + Color.green(b) * amount).toInt(),
    (Color.blue(a) * (1 - amount) + Color.blue(b) * amount).toInt(),
)
