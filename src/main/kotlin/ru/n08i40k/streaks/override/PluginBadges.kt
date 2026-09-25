package ru.n08i40k.streaks.override

import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.isClientVersionBelow

@Suppress("LocalVariableName")
object PluginBadges {
    // все классы принадлежат клиенту и могут отсутствовать, поэтому резолв ленивый
    private fun resolveCache(): Any? {
        val BadgesController =
            Class.forName("com.exteragram.messenger.badges.BadgesController")

        val BadgesController_INSTANCE =
            getField(BadgesController, "INSTANCE")

        val BadgesController_apiBadgeSource =
            getField(BadgesController_INSTANCE.type, "apiBadgeSource")

        val ApiBadgeSource_cache =
            getField(BadgesController_apiBadgeSource.type, "cache")

        val badgesController = BadgesController_INSTANCE.getAs<Any>(null)
            ?: return null

        val apiBadgeSource = BadgesController_apiBadgeSource.getAs<Any>(badgesController)
            ?: return null

        return ApiBadgeSource_cache.getAs<Any>(apiBadgeSource)
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
        val BadgeDTO =
            Class.forName("com.exteragram.messenger.api.dto.BadgeDTO")

        val ProfileStatus =
            Class.forName("com.exteragram.messenger.api.model.ProfileStatus")

        val badgesCache = resolveCache() ?: return

        // на версии 12.1.1 ConcurrentHashMap почему-то в неймспейсе $j, вместо java
        val ConcurrentHashMap_put = badgesCache::class.java
            .getDeclaredMethod("put", Any::class.java, Any::class.java)

        val BadgeDTO_constructor = BadgeDTO
            .getDeclaredConstructor(Long::class.java, String::class.java)
            .apply { isAccessible = true }

        val BadgeInfo_constructor =
            Class.forName("com.exteragram.messenger.badges.source.BadgeInfo")
                .let {
                    if (isClientVersionBelow("12.2.10"))
                        it.getDeclaredConstructor(BadgeDTO, ProfileStatus)
                    else
                        it.getDeclaredConstructor(BadgeDTO, ProfileStatus, Boolean::class.java)
                }
                .apply { isAccessible = true }

        // Используется DEVELOPER, ибо у дефолтного есть кнопка "Подробнее", которая может ввести в заблуждение
        val ProfileStatus_DEVELOPER = ProfileStatus.enumConstants!![1]

        TRUSTED_IDS.forEach { (id, text) ->
            val badge = BadgeDTO_constructor.newInstance(Emoji.DEFAULT_BADGE, text())

            val info = if (isClientVersionBelow("12.2.10"))
                BadgeInfo_constructor.newInstance(badge, ProfileStatus_DEVELOPER)
            else
                BadgeInfo_constructor.newInstance(badge, ProfileStatus_DEVELOPER, false)

            ConcurrentHashMap_put.invoke(badgesCache, id, info)
        }
    }

    @Throws(
        ClassNotFoundException::class,
        NoSuchFieldException::class,
        NoSuchMethodException::class
    )
    fun remove() {
        val badgesCache = resolveCache() ?: return

        val ConcurrentHashMap_remove = badgesCache::class.java
            .getDeclaredMethod("remove", Any::class.java)

        TRUSTED_IDS.forEach { (id, _) -> ConcurrentHashMap_remove.invoke(badgesCache, id) }
    }
}