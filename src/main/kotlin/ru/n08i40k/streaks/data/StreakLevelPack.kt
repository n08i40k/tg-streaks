package ru.n08i40k.streaks.data

import android.graphics.Color
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import kotlinx.collections.immutable.persistentListOf
import ru.n08i40k.streaks.i18n.Strings
import java.util.UUID

@Entity(
    tableName = "streak_emoji_packs", // TODO: rename table
    primaryKeys = ["id"]
)
data class StreakLevelPack(
    @ColumnInfo(name = "id") val id: UUID,
    @ColumnInfo(name = "based_on") val basedOn: UUID, // can't be based on user created packs

    @ColumnInfo(name = "name") val name: String,

    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "emojis") val emojis: Map<String, StreakEmojiInfo>
) {
    @delegate:Ignore
    val levels by lazy {
        emojis.entries
            .map {
                val length = extractLength(it.key)

                if (length == -1)
                    return@map null

                StreakLevel(it.key, length, it.value.documentId, it.value.accentColor)
            }
            .filterNotNull()
            .sortedBy { it.length }
    }

    @delegate:Ignore
    val isBuiltin by lazy { builtin.any { it.id == id } }

    @Ignore
    val sortedEmojis = emojis.toSortedMap { a, b -> extractLength(a).compareTo(extractLength(b)) }

    fun getCold(): StreakLevel =
        levels.first { it.id == "cold" }

    fun findNext(level: StreakLevel): StreakLevel? =
        levels.firstOrNull { it.length > level.length }

    fun findPrevious(level: StreakLevel): StreakLevel? =
        levels.lastOrNull { it.length < level.length }

    fun findById(id: String): StreakLevel? =
        levels.firstOrNull { it.id == id }

    fun findFirstVisible(): StreakLevel =
        levels.first { it.length >= Streak.MIN_VISIBLE_LENGTH }

    fun findByLengthApproximate(length: Int): StreakLevel =
        levels.findLast { it.length <= length } ?: getCold()

    companion object {
        val LENGTH_REGEX = Regex("^(\\d+)d$")

        fun extractLength(id: String): Int {
            if (id == "cold")
                return 0

            return LENGTH_REGEX
                .matchEntire(id)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it >= Streak.MIN_VISIBLE_LENGTH && "${it}d" == id }
                ?: -1
        }

        fun isValid(pack: StreakLevelPack): Boolean =
            pack.emojis.containsKey("cold") && pack.emojis.containsKey("3d") &&
                pack.emojis.keys.all { extractLength(it) >= 0 }

        private fun rgb(r: Int, g: Int, b: Int): Color =
            Color.valueOf(r.toFloat() / 255f, g.toFloat() / 255f, b.toFloat() / 255)

        // lazy because we need to wait for language pack load
        val builtin by lazy {
            persistentListOf(
                StreakLevelPack(
                    id = UUID.fromString("d8a1f6c4-3b27-4f5e-9c10-2a7b6e5d4c31"),
                    basedOn = UUID.fromString("d8a1f6c4-3b27-4f5e-9c10-2a7b6e5d4c31"), // same uuid, because null is not allowed
                    name = Strings.streak_level_pack_telegram_based_name(),
                    version = 1,
                    emojis = mapOf(
                        "cold" to StreakEmojiInfo(
                            5285071881815235305L,
                            rgb(175, 175, 175)
                        ),
                        "3d" to StreakEmojiInfo(
                            5285079178964672780L,
                            rgb(255, 154, 0)
                        ),
                        "10d" to StreakEmojiInfo(
                            5285274844789777412L,
                            rgb(255, 100, 0)
                        ),
                        "30d" to StreakEmojiInfo(
                            5285076623459129616L,
                            rgb(255, 61, 0)
                        ),
                        "100d" to StreakEmojiInfo(
                            5285003347022093599L,
                            rgb(255, 0, 200)
                        ),
                        "200d" to StreakEmojiInfo(
                            5285514817497504375L,
                            rgb(176, 0, 255)
                        ),
                    )
                ),
                StreakLevelPack(
                    id = UUID.fromString("b5ee4c62-062f-4597-a380-367b47eb479c"),
                    basedOn = UUID.fromString("b5ee4c62-062f-4597-a380-367b47eb479c"),
                    name = Strings.streak_level_pack_legacy_name(),
                    version = 1,
                    emojis = mapOf(
                        "cold" to StreakEmojiInfo(
                            5210842644938267782L,
                            rgb(175, 175, 175)
                        ),
                        "3d" to StreakEmojiInfo(
                            5210966086593323416L,
                            rgb(255, 154, 0)
                        ),
                        "10d" to StreakEmojiInfo(
                            5213167944527283652L,
                            rgb(255, 100, 0)
                        ),
                        "30d" to StreakEmojiInfo(
                            5213256365019011190L,
                            rgb(255, 61, 0)
                        ),
                        "100d" to StreakEmojiInfo(
                            5213345554309877801L,
                            rgb(255, 0, 200)
                        ),
                        "200d" to StreakEmojiInfo(
                            5213375623375916034L,
                            rgb(176, 0, 255)
                        ),
                    )
                )
            )
        }
    }
}
