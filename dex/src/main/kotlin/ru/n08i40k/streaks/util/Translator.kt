package ru.n08i40k.streaks.util

import ru.n08i40k.streaks.TranslationResolver

class Translator(private val translationResolver: TranslationResolver) {
    fun translate(key: String): String =
        translationResolver.apply(key) ?: key

    fun translate(key: String, replacements: Map<String, String>): String {
        var value = translate(key)

        replacements.forEach { (name, replacement) ->
            value = value.replace("{$name}", replacement)
        }

        return value
    }
}