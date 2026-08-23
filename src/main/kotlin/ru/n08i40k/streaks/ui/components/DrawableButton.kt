package ru.n08i40k.streaks.ui.components

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.widget.ImageView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme

// taken from com.exteragram.messenger.plugins.ui.components.PluginCell
class DrawableButton(context: Context) : ImageView(context) {
    constructor(context: Context, resId: Int, dangerous: Boolean, onClickListener: OnClickListener) : this(context) {
        // as setDangerous already has been called with false as argument
        if (dangerous)
            setDangerous(dangerous)

        setImageResource(resId)
        setOnClickListener(onClickListener)
    }

    init {
        setScaleType(ScaleType.CENTER)
        setDangerous(false)
    }

    fun setDangerous(dangerous: Boolean) {
        val color = if (dangerous)
            Theme.key_text_RedRegular
        else
            Theme.key_windowBackgroundWhiteGrayIcon

        colorFilter =
            PorterDuffColorFilter(Theme.getColor(color), PorterDuff.Mode.MULTIPLY)

        val bgColor = if (dangerous)
            Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.12f)
        else
            Theme.getColor(Theme.key_dialogButtonSelector)

        background = Theme.createSelectorDrawable(bgColor, 1, dp(20.0f))
    }

    fun setButtonEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
    }
}