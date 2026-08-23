package ru.n08i40k.streaks.override

import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.isClientVersionBelow

object PluginBadges {
    // все классы принадлежат клиенту и могут отсутствовать, поэтому резолв ленивый
    private object Fields {
        val INSTANCE by lazy {
            getField(
                Class.forName("com.exteragram.messenger.badges.BadgesController"),
                "INSTANCE"
            )
        }

        val API_BADGE_SOURCE by lazy {
            getField(INSTANCE.type, "apiBadgeSource")
        }

        val CACHE by lazy {
            getField(API_BADGE_SOURCE.type, "cache")
        }
    }

    private fun resolveCache(): Any? {
        val controller = Fields.INSTANCE.getAs<Any>(null)
            ?: return null

        val apiBadgeSource = Fields.API_BADGE_SOURCE.getAs<Any>(controller)
            ?: return null

        return Fields.CACHE.getAs<Any>(apiBadgeSource)
    }

    val TRUSTED_IDS = mapOf(
        Pair(TrustedSources.LEAD.id, Strings.badge_me_text),          // me
        Pair(TrustedSources.CHANNEL.id, Strings.badge_channel_text),  // channel
        Pair(TrustedSources.CHAT.id, Strings.badge_chat_text)         // channel chat
    )

    @Suppress("LocalVariableName")
    fun add() {
        if (!isClientVersionBelow("12.6.4") && isClientVersionBelow("12.8.0"))
            return

        val BadgeDTO = Class.forName("com.exteragram.messenger.api.dto.BadgeDTO")
        val ProfileStatus = Class.forName("com.exteragram.messenger.api.model.ProfileStatus")

        val cache = resolveCache() ?: return

        // на версии 12.1.1 ConcurrentHashMap почему-то в неймспейсе $j, вместо java
        val cache_set = cache::class.java
            .getDeclaredMethod("put", Any::class.java, Any::class.java)

        val BadgeDTO_ctor = BadgeDTO
            .getDeclaredConstructor(Long::class.java, String::class.java)
            .apply { isAccessible = true }

        val BadgeInfo_ctor = Class
            .forName("com.exteragram.messenger.badges.source.BadgeInfo")
            .let {
                if (isClientVersionBelow("12.2.10"))
                    it.getDeclaredConstructor(BadgeDTO, ProfileStatus)
                else
                    it.getDeclaredConstructor(BadgeDTO, ProfileStatus, Boolean::class.java)
            }
            .apply { isAccessible = true }

        // Используется DEVELOPER, ибо у дефолтного есть кнопка "Подробнее", которая может ввести в заблуждение
        val profileStatus = ProfileStatus.enumConstants!![1]

        TRUSTED_IDS.forEach { (id, text) ->
            val badge = BadgeDTO_ctor.newInstance(Emoji.DEFAULT_BADGE, text())

            val info = if (isClientVersionBelow("12.2.10"))
                BadgeInfo_ctor.newInstance(badge, profileStatus)
            else
                BadgeInfo_ctor.newInstance(badge, profileStatus, false)

            cache_set.invoke(cache, id, info)
        }
    }

    @Suppress("LocalVariableName")
    fun remove() {
        val cache = resolveCache() ?: return

        val cache_remove = cache::class.java
            .getDeclaredMethod("remove", Any::class.java)

        TRUSTED_IDS.forEach { (id, _) -> cache_remove.invoke(cache, id) }
    }
}