package ru.n08i40k.streaks.extension

import org.telegram.messenger.LocaleController
import java.util.Locale
import kotlin.text.trim

private val VALID_ISO_LANGUAGES = Locale.getISOLanguages().toHashSet()

fun LocaleController.resolveLanguageCode(): String {
    val locale = this.currentLocaleInfo
        ?.let { if (it.hasBaseLang()) it.baseLangCode else (it.langCode ?: it.shortName) }
        ?: this.currentLocale?.language
        ?: "en"

    val code = locale
        .trim()
        .lowercase()
        .replace('-', '_')
        .substringBefore('_')
        .ifEmpty { "en" }

    return if (code in VALID_ISO_LANGUAGES) code else "en"
}
