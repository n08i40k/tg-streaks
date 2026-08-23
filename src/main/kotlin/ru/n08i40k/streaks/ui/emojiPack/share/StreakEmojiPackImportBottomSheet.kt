package ru.n08i40k.streaks.ui.emojiPack.share

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.emojiPack.select.StreakEmojiPackCell

class StreakEmojiPackImportBottomSheet(
    context: Context,
    pack: StreakEmojiPack,
    replace: Boolean,
    private val onImport: () -> Unit
) : BottomSheet(context, false), EjectNotifier.Delegate {

    private val unsubscribeFromEject = EjectNotifier.subscribe(this)

    override fun onEject() = dismiss()

    override fun onStop() {
        unsubscribeFromEject()
        super.onStop()
    }

    init {
        setCustomView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
            setPadding(0, dp(16f), 0, 0)

            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER

                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                text = Strings.sheet_emoji_pack_import_title()
            }, LayoutHelper.createLinear(-1, -2, 16f, 0f, 16f, 0f))

            addView(
                StreakEmojiPackCell(context)
                    .apply { setEmojiPack(pack, false) },
                LayoutHelper.createLinear(-1, -2, 4f, 12f, 4f, 0f)
            )

            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                gravity = Gravity.CENTER

                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                text = if (replace)
                    Strings.sheet_emoji_pack_import_desc_replace()
                else
                    Strings.sheet_emoji_pack_import_desc_add()
            }, LayoutHelper.createLinear(-1, -2, 24f, 4f, 24f, 0f))

            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER

                setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
                background = Theme.AdaptiveRipple.filledRect(
                    Theme.getColor(Theme.key_featuredStickers_addButton),
                    6f
                )

                text = if (replace)
                    Strings.sheet_emoji_pack_import_replace()
                else
                    Strings.sheet_emoji_pack_import_add()

                setOnClickListener {
                    onImport()
                    dismiss()
                }
            }, LayoutHelper.createLinear(-1, 48, 16f, 16f, 16f, 8f))
        })
    }
}
