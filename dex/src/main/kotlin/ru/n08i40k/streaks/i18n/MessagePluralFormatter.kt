package ru.n08i40k.streaks.i18n

import de.comahe.i18n4k.Locale
import de.comahe.i18n4k.messages.formatter.MessageFormatContext
import de.comahe.i18n4k.messages.formatter.MessageParameters
import de.comahe.i18n4k.messages.formatter.MessageValueFormatter
import de.comahe.i18n4k.messages.formatter.parsing.MessagePart
import de.comahe.i18n4k.messages.formatter.parsing.StylePart
import de.comahe.i18n4k.messages.formatter.parsing.StylePartArgument
import de.comahe.i18n4k.messages.formatter.parsing.StylePartList
import de.comahe.i18n4k.messages.formatter.parsing.StylePartMessage
import kotlin.math.abs

object MessagePluralFormatter : MessageValueFormatter {
    private const val OTHER = "other"

    override val typeId: String
        get() = "plural"

    override fun format(
        result: StringBuilder,
        value: Any?,
        typeId: CharSequence,
        style: StylePart?,
        parameters: MessageParameters,
        locale: Locale,
        context: MessageFormatContext
    ) {
        if (style == null)
            return

        val count = when (value) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull() ?: 0
        }

        matchingPart(style, category(count, locale.language))
            ?.format(result, parameters, locale, context)
    }

    private fun matchingPart(style: StylePart, category: String): MessagePart? {
        if (style is StylePartMessage)
            return style.messagePart

        var found = false
        if (style is StylePartList) {
            for (part in style.list) {
                if (part is StylePartArgument && (part.value == category || part.value == OTHER))
                    found = true
                if (part is StylePartMessage && found)
                    return part.messagePart
            }
        }
        return null
    }

    private fun category(count: Int, lang: String): String {
        val n = abs(count)

        return when (lang) {
            "ru" -> {
                val mod10 = n % 10
                val mod100 = n % 100

                when (mod10) {
                    1 if mod100 != 11 -> "one"
                    in 2..4 if mod100 !in 12..14 -> "few"
                    else -> "many"
                }
            }

            else -> if (n == 1) "one" else "other"
        }
    }
}
