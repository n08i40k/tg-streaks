package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities

@Suppress("NOTHING_TO_INLINE")
inline fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())
