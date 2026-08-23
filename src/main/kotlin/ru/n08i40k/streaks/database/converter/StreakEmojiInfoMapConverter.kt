package ru.n08i40k.streaks.database.converter

import android.graphics.Color
import androidx.room.TypeConverter
import org.json.JSONObject
import ru.n08i40k.streaks.data.StreakEmojiInfo

class StreakEmojiInfoMapConverter {
    @TypeConverter
    fun fromEmojis(emojis: Map<String, StreakEmojiInfo>): String {
        val json = JSONObject()

        for ((uid, emoji) in emojis) {
            json.put(
                uid,
                JSONObject()
                    .put("document_id", emoji.documentId)
                    .put("accent_color", emoji.accentColor?.toArgb() ?: JSONObject.NULL)
            )
        }

        return json.toString()
    }

    @TypeConverter
    fun toEmojis(value: String): Map<String, StreakEmojiInfo> {
        val json = JSONObject(value)
        val emojis = HashMap<String, StreakEmojiInfo>(json.length())

        for (uid in json.keys()) {
            val emoji = json.getJSONObject(uid)

            emojis[uid] = StreakEmojiInfo(
                documentId = emoji.getLong("document_id"),
                accentColor =
                    if (emoji.isNull("accent_color")) null
                    else Color.valueOf(emoji.getInt("accent_color"))
            )
        }

        return emojis
    }
}
