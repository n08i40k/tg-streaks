package ru.n08i40k.streaks.override

import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.`ApiBadgeSource$cache`
import ru.n08i40k.streaks.util.`BadgeDTO$$init`
import ru.n08i40k.streaks.util.`BadgeInfo$$init`
import ru.n08i40k.streaks.util.`BadgesController$apiBadgeSource`
import ru.n08i40k.streaks.util.ProfileStatus
import ru.n08i40k.streaks.util.isClientVersionBelow

@Suppress("LocalVariableName")
object PluginBadges {
    // все классы принадлежат клиенту и могут отсутствовать, поэтому резолв ленивый
    private fun resolveCache(): Any? {
        val apiBadgeSource = `BadgesController$apiBadgeSource`
            ?.invokeExact()
            ?: return null

        return `ApiBadgeSource$cache`?.invokeExact(apiBadgeSource)
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
        `BadgeInfo$$init` ?: return
        ProfileStatus ?: return

        // на версии 12.1.1 ConcurrentHashMap почему-то в неймспейсе $j, вместо java
        val `ConcurrentHashMap$put` = badgesCache::class.java
            .getDeclaredMethod("put", Any::class.java, Any::class.java)

        // Используется DEVELOPER, ибо у дефолтного есть кнопка "Подробнее", которая может ввести в заблуждение
        val `ProfileStatus$DEVELOPER` = ProfileStatus.enumConstants!![1]

        TRUSTED_IDS.forEach { (id, text) ->
            val badge = `BadgeDTO$$init`.newInstance(Emoji.DEFAULT_BADGE, text())

            val badgeInfo = if (isClientVersionBelow("12.2.10"))
                `BadgeInfo$$init`.newInstance(badge, `ProfileStatus$DEVELOPER`)
            else
                `BadgeInfo$$init`.newInstance(badge, `ProfileStatus$DEVELOPER`, false)

            `ConcurrentHashMap$put`.invoke(badgesCache, id, badgeInfo)
        }
    }

    @Throws(
        ClassNotFoundException::class,
        NoSuchFieldException::class,
        NoSuchMethodException::class
    )
    fun remove() {
        val badgesCache = resolveCache() ?: return

        val `ConcurrentHashMap$remove` = badgesCache::class.java
            .getDeclaredMethod("remove", Any::class.java)

        TRUSTED_IDS.forEach { (id, _) -> `ConcurrentHashMap$remove`.invoke(badgesCache, id) }
    }
}