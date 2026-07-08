package ru.n08i40k.streaks.extension

import org.telegram.messenger.LocaleController
import kotlin.text.trim

fun LocaleController.resolveLanguageCode(): String {
    val locale = this.currentLocaleInfo
        ?.let { it.langCode ?: it.shortName }
        ?: this.currentLocale?.language
        ?: "en"

    return locale
        .trim()
        .lowercase()
        .replace('-', '_')
        .substringBefore('_')
        .ifEmpty { "en" }
}
