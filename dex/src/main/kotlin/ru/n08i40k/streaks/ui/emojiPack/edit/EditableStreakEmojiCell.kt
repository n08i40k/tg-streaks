package ru.n08i40k.streaks.ui.emojiPack.edit

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R.drawable
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stars
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import org.telegram.ui.SelectAnimatedEmojiDialog
import org.telegram.ui.SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.ui.components.AccentColorBottomSheet
import ru.n08i40k.streaks.ui.components.ColorSwatchView
import ru.n08i40k.streaks.ui.components.DrawableButton
import ru.n08i40k.streaks.util.AnimatedEmojiView
import java.lang.Math.clamp
import kotlin.math.max
import kotlin.math.min

class EditableStreakEmojiCell(
    context: Context,
) : LinearLayout(context) {

    // popup
    private var emojiPickerPopup: SelectAnimatedEmojiDialogWindow? = null

    // listeners
    private var onResetListener: (() -> Unit)? = null
    private var onUpdateListener: ((StreakEmojiInfo) -> Unit)? = null

    // saved state
    private var emojiInfo: StreakEmojiInfo? = null

    // views
    private val nameTextView = TextView(context)

    private val emojiImageView = BackupImageView(context)
        .apply { setOnClickListener { openEmojiPicker() } }

    private val resetButton = DrawableButton(context, drawable.msg_reset, false) {
        onResetListener?.invoke()
    }

    private val colorSwatchView = ColorSwatchView(context)
        .apply { setOnClickListener { openColorPicker() } }

    fun setName(name: String) {
        nameTextView.text = name
    }

    fun setEmojiInfo(emojiInfo: StreakEmojiInfo) {
        this.emojiInfo = emojiInfo

        colorSwatchView.setColor(emojiInfo.accentColor)

        AnimatedEmojiView.apply(
            emojiImageView,
            emojiInfo.documentId,
            dp(32f),
            emojiInfo.accentColor?.toArgb()
        )
    }

    fun setChanged(changed: Boolean) {
        resetButton.visibility = if (changed) VISIBLE else GONE
    }

    fun setOnResetListener(listener: () -> Unit) {
        this.onResetListener = listener
    }

    fun setOnUpdateListener(listener: (StreakEmojiInfo) -> Unit) {
        this.onUpdateListener = listener
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12f), dp(6f), dp(12f), dp(6f)) }

        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        background = Theme.createSimpleSelectorRoundRectDrawable(
            dp(12f),
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector)
        )

        setPadding(dp(10.5f), dp(8f), dp(10.5f), dp(8f))

        addView(nameTextView.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

            setPadding(dp(8f), 0, 0, 0)

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })

        addView(resetButton, LayoutParams(dp(32f), dp(32f)))
        addView(colorSwatchView, LayoutHelper.createLinear(32, 32, 8f, 0f, 0f, 0f))
        addView(emojiImageView, LayoutHelper.createLinear(32, 32, 8f, 0f, 0f, 0f))
    }

    private fun openColorPicker() {
        val emojiInfo = this.emojiInfo ?: return

        AccentColorBottomSheet(context, emojiInfo.accentColor) { color ->
            onUpdateListener?.invoke(emojiInfo.copy(accentColor = color))
        }.show()
    }

    private fun openEmojiPicker() {
        val popupWidth = min(dp(340f - 16f), (AndroidUtilities.displaySize.x * 0.95f).toInt())
        val popupHeight =
            min(dp(410f - 16f - 64f), (AndroidUtilities.displaySize.y * 0.75f).toInt())

        val loc = intArrayOf(0, 0)
        emojiImageView.getLocationOnScreen(loc)

        val centerX = loc[0] + emojiImageView.width / 2
        val centerY = loc[1] + emojiImageView.height / 2

        val left = clamp(
            (centerX - popupWidth / 2).toLong(),
            0,
            AndroidUtilities.displaySize.x - popupWidth
        )

        val xoff = left - loc[0]
        val ecenter = centerX - left

        val down = centerY > AndroidUtilities.displaySize.y / 2

        val yoff = if (down)
            -(emojiImageView.height / 2) + dp(12f) - popupHeight
        else
            -(emojiImageView.height / 2) - dp(16f)

        val layout = object : SelectAnimatedEmojiDialog(
            LaunchActivity.getSafeLastFragment(),
            context,
            true,
            max(0, ecenter),
            if (down) 12 /*TYPE_EMOJI_STATUS_TOP*/ else 0 /*TYPE_EMOJI_STATUS*/,
            true,
            null,
            if (down) 24 else 16
        ) {
            override fun onEmojiSelected(
                view: View?,
                documentId: Long?,
                document: TLRPC.Document?,
                gift: TL_stars.TL_starGiftUnique?,
                until: Int?
            ) {
                if (documentId == null)
                    return

                emojiInfo?.let { onUpdateListener?.invoke(it.copy(documentId = documentId)) }
                emojiPickerPopup?.dismiss()
                emojiPickerPopup = null
            }
        }

        layout.apply {
            setSelected(emojiInfo?.documentId)
            setSaveState(3)
        }

        emojiPickerPopup = object : SelectAnimatedEmojiDialogWindow(
            layout,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ) {
            override fun dismiss() {
                super.dismiss()
                emojiPickerPopup = null
            }
        }.apply {
            showAsDropDown(
                emojiImageView,
                xoff,
                yoff,
                Gravity.TOP or Gravity.LEFT
            )

            dimBehind()
        }
    }

    override fun onDetachedFromWindow() {
        emojiPickerPopup?.dismiss()
        emojiPickerPopup = null

        super.onDetachedFromWindow()
    }
}
