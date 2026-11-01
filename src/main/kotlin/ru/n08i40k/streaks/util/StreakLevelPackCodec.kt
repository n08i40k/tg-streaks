package ru.n08i40k.streaks.util

import android.graphics.Color
import android.net.Uri
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.data.StreakLevelPack
import java.util.UUID

// tg-streaks:emoji-packs:import:{version}:{id}:{basedOn}:{escaped-name}[:{key}:{documentId}:{accentColor}]+
object StreakLevelPackCodec {
    private const val MAX_NAME_LENGTH = 64
    private const val NO_ACCENT_COLOR = "none"

    fun encode(pack: StreakLevelPack): String = buildString {
        append(ServiceMessage.LEVEL_PACK_IMPORT_PREFIX)

        append(2)
        append(':').append(pack.id)
        append(':').append(pack.basedOn)
        append(':').append(Uri.encode(pack.name))

        for ((key, emoji) in pack.sortedEmojis) {
            append(':').append(key)
            append(':').append(emoji.documentId)
            append(':').append(
                emoji.accentColor
                    ?.toArgb()
                    ?.toUInt()
                    ?.toString(16)
                    ?.padStart(8, '0')
                    ?: NO_ACCENT_COLOR
            )
        }
    }

    fun decode(text: String): StreakLevelPack? {
        if (!ServiceMessage.isLevelPackImport(text))
            return null

        val parts = text
            .removePrefix(ServiceMessage.LEVEL_PACK_IMPORT_PREFIX)
            .split(':')

        if (parts.size < 7 || (parts.size - 4) % 3 != 0)
            return null

        val version = parts[0].toIntOrNull()?.takeIf { it in 1..2 } ?: return null
        val id = parts[1].toUuidOrNull() ?: return null
        val basedOn = parts[2].toUuidOrNull() ?: return null

        // a shared pack must never shadow a builtin one
        if (StreakLevelPack.builtin.any { it.id == id })
            return null

        val parent = StreakLevelPack.builtin.find { it.id == basedOn } ?: return null

        val name = Uri.decode(parts[3])
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { id.toString() }

        // Version 1 inherits missing entries; version 2 carries the complete level set.
        val emojis = if (version == 1) parent.emojis.toMutableMap() else mutableMapOf()
        val seen = mutableSetOf<String>()

        for (i in 4..<parts.size step 3) {
            val key = parts[i]

            if (!seen.add(key)
                || StreakLevelPack.extractLength(key) < 0
                || (version == 1 && key !in parent.emojis)
            ) return null

            val documentId = parts[i + 1].toLongOrNull()
                ?: return null

            val accentColor = when (val raw = parts[i + 2]) {
                NO_ACCENT_COLOR -> null
                else -> raw.toUIntOrNull(16)?.toInt()?.let(Color::valueOf)
                    ?: return null
            }

            emojis[key] = StreakEmojiInfo(documentId, accentColor)
        }

        return StreakLevelPack(id, basedOn, name, version, emojis)
            .takeIf(StreakLevelPack::isValid)
    }

    private fun String.toUuidOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }
}
