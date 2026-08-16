package ru.n08i40k.streaks.util

import android.graphics.Color
import android.net.Uri
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.data.StreakEmojiPack
import java.util.UUID

// tg-streaks:emoji-packs:import:{version}:{id}:{basedOn}:{escaped-name}[:{key}:{documentId}:{accentColor}]+
object StreakEmojiPackCodec {
    private const val MAX_NAME_LENGTH = 64
    private const val NO_ACCENT_COLOR = "none"

    fun encode(pack: StreakEmojiPack): String = buildString {
        append(ServiceMessage.EMOJI_PACK_IMPORT_PREFIX)

        append(pack.version)
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

    fun decode(text: String): StreakEmojiPack? {
        if (!ServiceMessage.isEmojiPackImport(text))
            return null

        val parts = text
            .removePrefix(ServiceMessage.EMOJI_PACK_IMPORT_PREFIX)
            .split(':')

        if (parts.size < 7 || (parts.size - 4) % 3 != 0)
            return null

        val version = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val id = parts[1].toUuidOrNull() ?: return null
        val basedOn = parts[2].toUuidOrNull() ?: return null

        // a shared pack must never shadow a builtin one
        if (StreakEmojiPack.builtin.any { it.id == id })
            return null

        val parent = StreakEmojiPack.builtin.find { it.id == basedOn } ?: return null

        val name = Uri.decode(parts[3])
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { id.toString() }

        // unknown keys are rejected, missing ones are inherited from the parent
        val emojis = parent.emojis.toMutableMap()

        for (i in 4..<parts.size step 3) {
            val key = parts[i].takeIf(emojis::containsKey) ?: return null
            val documentId = parts[i + 1].toLongOrNull() ?: return null

            val accentColor = when (val raw = parts[i + 2]) {
                NO_ACCENT_COLOR -> null
                else -> raw.toUIntOrNull(16)?.toInt()?.let(Color::valueOf) ?: return null
            }

            emojis[key] = StreakEmojiInfo(documentId, accentColor)
        }

        return StreakEmojiPack(id, basedOn, name, version, emojis)
    }

    private fun String.toUuidOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }
}
