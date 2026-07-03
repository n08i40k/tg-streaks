package ru.n08i40k.streaks.override

import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.isClientVersionBelow

object PluginBadges {
    val TRUSTED_IDS = mapOf(
        Pair(TrustedSources.LEAD.id, TranslationKey.Badge.ME),          // me
        Pair(TrustedSources.CHANNEL.id, TranslationKey.Badge.CHANNEL),  // channel
        Pair(TrustedSources.CHAT.id, TranslationKey.Badge.CHAT)         // channel chat
    )

    @Suppress("LocalVariableName")
    fun add() {
        if (!isClientVersionBelow("12.6.4") && isClientVersionBelow("12.8.0"))
            return

        val BadgeDTO = Class.forName("com.exteragram.messenger.api.dto.BadgeDTO")
        val ProfileStatus = Class.forName("com.exteragram.messenger.api.model.ProfileStatus")

        val cache = run {
            val BadgesController = Class.forName("com.exteragram.messenger.badges.BadgesController")

            val controller = getFieldValue<Any>(BadgesController, null, "INSTANCE")
                ?: return

            val apiBadgeSource = getFieldValue<Any>(controller, "apiBadgeSource")
                ?: return

            getFieldValue<Any>(apiBadgeSource, "cache")
                ?: return
        }

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
            val badge = BadgeDTO_ctor.newInstance(Emoji.DEFAULT_BADGE, Translator.translate(text))

            val info = if (isClientVersionBelow("12.2.10"))
                BadgeInfo_ctor.newInstance(badge, profileStatus)
            else
                BadgeInfo_ctor.newInstance(badge, profileStatus, false)

            cache_set.invoke(cache, id, info)
        }
    }

    @Suppress("LocalVariableName")
    fun remove() {
        val cache = run {
            val BadgesController = Class.forName("com.exteragram.messenger.badges.BadgesController")

            val controller = getFieldValue<Any>(BadgesController, null, "INSTANCE")
                ?: return

            val apiBadgeSource = getFieldValue<Any>(controller, "apiBadgeSource")
                ?: return

            getFieldValue<Any>(apiBadgeSource, "cache")
                ?: return
        }

        val cache_remove = cache::class.java
            .getDeclaredMethod("remove", Any::class.java)

        TRUSTED_IDS.forEach { (id, _) -> cache_remove.invoke(cache, id) }
    }
}