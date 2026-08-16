package ru.n08i40k.streaks.ui.components

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ColorPicker
import org.telegram.ui.Components.LayoutHelper
import ru.n08i40k.streaks.i18n.Strings

class AccentColorBottomSheet(
    context: Context,
    initial: Color?,
    private val onSelected: (Color?) -> Unit
) : BottomSheet(context, true) {

    private val initialColor: Int =
        initial?.toArgb() ?: Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon)

    private object Delegate : ColorPicker.ColorPickerDelegate {
        override fun setColor(color: Int, num: Int, applyNow: Boolean) {}
        override fun openThemeCreate(share: Boolean) {}
        override fun deleteTheme() {}
        override fun getDefaultColor(num: Int): Int = 0
    }

    private val colorPicker = object : ColorPicker(context, false, Delegate) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(dp(300f), MeasureSpec.EXACTLY)
            )
        }
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER

            if (filled) {
                setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
                background = Theme.AdaptiveRipple.filledRect(
                    Theme.getColor(Theme.key_featuredStickers_addButton),
                    6f
                )
            } else {
                setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton))
                background = Theme.AdaptiveRipple.filledRect(
                    Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f),
                    6f
                )
            }

            setText(text)
            setOnClickListener { onClick() }
        }

    init {
        colorPicker.setColor(initialColor, 0)
        colorPicker.setType(-1, false, 1, 1, false, 0, false)

        setCustomView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(0, dp(16f), 0, 0)

            addView(colorPicker, LayoutHelper.createLinear(-1, -2))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    button(Strings.menu_streak_emoji_pack_edit_color_none(), false) {
                        onSelected(null)
                        dismiss()
                    },
                    LayoutHelper.createLinear(0, 48, 1f)
                )

                addView(View(context), LayoutHelper.createLinear(8, 1))

                addView(
                    button(Strings.menu_streak_emoji_pack_edit_color_apply(), true) {
                        onSelected(Color.valueOf(colorPicker.color))
                        dismiss()
                    },
                    LayoutHelper.createLinear(0, 48, 1f)
                )
            }, LayoutHelper.createLinear(-1, -2, 16f, 8f, 16f, 8f))
        })
    }
}
