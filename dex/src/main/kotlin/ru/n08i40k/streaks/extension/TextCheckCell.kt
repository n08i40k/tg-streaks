package ru.n08i40k.streaks.extension

import org.telegram.ui.Cells.TextCheckCell

fun TextCheckCell.setTextAndValueAndCheckCompat(
    text: String,
    value: String,
    checked: Boolean,
    multiline: Boolean,
    divider: Boolean,
) {
    try {
        this.javaClass
            .getDeclaredMethod(
                "setTextAndValueAndCheck",
                CharSequence::class.java,
                String::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
            )
            .invoke(this, text, value, checked, multiline, divider)
        return
    } catch (_: NoSuchMethodException) {
    }

    try {
        this.javaClass
            .getDeclaredMethod(
                "setTextAndValueAndCheck",
                String::class.java,
                String::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
            )
            .invoke(this, text, value, checked, multiline, divider)
    } catch (_: NoSuchMethodException) {
    }
}