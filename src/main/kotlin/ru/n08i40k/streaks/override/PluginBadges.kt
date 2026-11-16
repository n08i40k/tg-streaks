package ru.n08i40k.streaks.override

import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.`ApiBadgeSource$cache`
import ru.n08i40k.streaks.util.`BadgeDTO$$init`
import ru.n08i40k.streaks.util.`BadgeInfo$$init$$12_1_1`
import ru.n08i40k.streaks.util.`BadgeInfo$$init$$12_2_10`
import ru.n08i40k.streaks.util.`BadgesController$apiBadgeSource`
import ru.n08i40k.streaks.util.ProfileStatus

@Suppress("LocalVariableName")
object PluginBadges {
    // все классы принадлежат клиенту и могут отсутствовать, поэтому резолв ленивый;
    // кэш — j$.util.concurrent.ConcurrentHashMap или java.util.concurrent.ConcurrentHashMap, оба реализуют Map
    @Suppress("UNCHECKED_CAST")
    private fun resolveCache(): MutableMap<Any?, Any?>? {
        val apiBadgeSource = `BadgesController$apiBadgeSource`
            ?.invokeExact()
            ?: return null

        return `ApiBadgeSource$cache`?.invokeExact(apiBadgeSource) as MutableMap<Any?, Any?>?
    }

    val TRUSTED_IDS = mapOf(
        Pair(TrustedSources.LEAD.id, Strings.badge_me_text),          // me
        Pair(TrustedSources.CHANNEL.id, Strings.badge_channel_text),  // channel
        Pair(TrustedSources.CHAT.id, Strings.badge_chat_text)         // channel chat
    )

    @Throws(
        ClassNotFoundException::class,
        NoSuchFieldException::class,
        NoSuchMethodException::class
    )
    fun add() {
        val badgesCache = resolveCache()
            ?: return

        // ensure
        `BadgeDTO$$init` ?: return
        if (`BadgeInfo$$init$$12_2_10` == null && `BadgeInfo$$init$$12_1_1` == null) return
        ProfileStatus ?: return

        // Используется DEVELOPER, ибо у дефолтного есть кнопка "Подробнее", которая может ввести в заблуждение
        val `ProfileStatus$DEVELOPER` = ProfileStatus.enumConstants!![1]

        TRUSTED_IDS.forEach { (id, text) ->
            val badge = `BadgeDTO$$init`.invoke(Emoji.DEFAULT_BADGE, text())

            val badgeInfo = `BadgeInfo$$init$$12_2_10`?.invoke(badge, `ProfileStatus$DEVELOPER`, false)
                ?: `BadgeInfo$$init$$12_1_1`!!.invoke(badge, `ProfileStatus$DEVELOPER`)

            badgesCache[id] = badgeInfo
        }
    }

    @Throws(
        ClassNotFoundException::class,
        NoSuchFieldException::class,
        NoSuchMethodException::class
    )
    fun remove() {
        val badgesCache = resolveCache() ?: return

        TRUSTED_IDS.forEach { (id, _) -> badgesCache.remove(id) }
    }
}