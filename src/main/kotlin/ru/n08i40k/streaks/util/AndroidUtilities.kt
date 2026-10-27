package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities

inline fun <reified T: Number> dp(value: T): Int = AndroidUtilities.dp(value.toFloat())
