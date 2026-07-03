package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.TranslationResolver

object Translator {
    @Volatile private var resolver: TranslationResolver? = null

    fun setResolver(resolver: TranslationResolver?) {
        this.resolver = resolver
    }

    fun translate(key: String): String = resolver?.apply(key) ?: key

    fun translate(key: String, replacements: Map<String, String>): String {
        var value = translate(key)

        replacements.forEach { (name, replacement) ->
            value = value.replace("{$name}", replacement)
        }

        return value
    }
}
