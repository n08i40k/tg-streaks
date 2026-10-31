package ru.n08i40k.streaks.guide

import android.net.Uri

internal object StreakGuideLinks {
    private const val HOST = "streaks"
    private const val PATH = "guides"

    fun of(id: String): String =
        "tg://$HOST/$PATH/$id"

    fun parse(uri: Uri): String? {
        if (!uri.scheme.equals("tg", ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true))
            return null

        val segments = uri.pathSegments

        return when {
            segments.firstOrNull() != PATH -> null
            segments.size == 1 -> ""
            segments.size == 2 -> segments[1]
            else -> null
        }
    }
}
