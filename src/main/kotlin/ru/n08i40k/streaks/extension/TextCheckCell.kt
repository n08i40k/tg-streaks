package ru.n08i40k.streaks.extension

import org.telegram.ui.Cells.TextCheckCell
import ru.n08i40k.streaks.util.`TextCheckCell$setTextAndValueAndCheck$$12_1_1`
import ru.n08i40k.streaks.util.`TextCheckCell$setTextAndValueAndCheck$$12_5_1`

fun TextCheckCell.setTextAndValueAndCheckCompat(
    text: String,
    value: String,
    checked: Boolean,
    multiline: Boolean,
    divider: Boolean,
) {
    (`TextCheckCell$setTextAndValueAndCheck$$12_5_1` ?: `TextCheckCell$setTextAndValueAndCheck$$12_1_1`)
        ?.invoke(this, text, value, checked, multiline, divider)
}
