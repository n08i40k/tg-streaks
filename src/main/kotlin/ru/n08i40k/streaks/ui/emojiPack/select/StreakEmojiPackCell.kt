package ru.n08i40k.streaks.ui.emojiPack.select

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R.drawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Components.RadioButton
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.ui.components.DrawableButton
import java.util.UUID

class StreakEmojiPackCell(
    context: Context,
    private val withActions: Boolean,
) : LinearLayout(context) {

    enum class Action {
        SHARE,
        EDIT,
        INHERIT,
        DELETE
    }

    constructor(context: Context) : this(context, false)

    private var emojiPack: StreakEmojiPack? = null

    private val nameTextView = TextView(context)
    private val streakEmojiListView = StreakEmojiListView(context)
    private val selectedRadioButton = RadioButton(context)

    private val shareButton = DrawableButton(context, drawable.msg_share, false) {
        actionListener?.invoke(Action.SHARE, emojiPack?.id ?: return@DrawableButton)
    }

    private val editButton = DrawableButton(context, drawable.msg_edit, false) {
        actionListener?.invoke(Action.EDIT, emojiPack?.id ?: return@DrawableButton)
    }

    private val inheritButton = DrawableButton(context, drawable.msg_add, false) {
        actionListener?.invoke(Action.INHERIT, emojiPack?.id ?: return@DrawableButton)
    }

    private val deleteButton = DrawableButton(context, drawable.msg_delete, true) {
        actionListener?.invoke(Action.DELETE, emojiPack?.id ?: return@DrawableButton)
    }

    private var actionListener: ((Action, UUID) -> Unit)? = null

    fun setActionListener(listener: (Action, UUID) -> Unit) {
        this.actionListener = listener
    }

    fun setRecycledViewPool(pool: RecyclerView.RecycledViewPool) =
        streakEmojiListView.setRecycledViewPool(pool)

    fun setEmojiPack(emojiPack: StreakEmojiPack, builtin: Boolean) {
        this.emojiPack = emojiPack

        nameTextView.text = emojiPack.name
        streakEmojiListView.setItems(emojiPack.sortedEmojis.values.toList())

        shareButton.setButtonEnabled(!builtin)
        editButton.setButtonEnabled(!builtin)
        inheritButton.setButtonEnabled(builtin)
        deleteButton.setButtonEnabled(!builtin)
    }

    fun setChecked(checked: Boolean) =
        selectedRadioButton.setChecked(checked, true)

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12f), dp(6f), dp(12f), dp(6f)) }

        orientation = VERTICAL

        background = Theme.createSimpleSelectorRoundRectDrawable(
            dp(12f),
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector)
        )

        setPadding(
            dp(10.5f),
            dp(8f),
            dp(10.5f),
            dp(8f)
        )

        addView(LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )

            gravity = Gravity.CENTER_VERTICAL
            orientation = HORIZONTAL

            addView(LinearLayout(context).apply {
                layoutParams = LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )

                orientation = VERTICAL

                addView(nameTextView.apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )

                    setPadding(dp(8f), 0, 0, dp(8f))

                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    maxLines = 1
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })

                addView(streakEmojiListView.apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )
                })
            })

            addView(selectedRadioButton.apply {
                layoutParams = LayoutParams(
                    dp(24f),
                    dp(24f)
                ).apply { gravity = Gravity.CENTER_VERTICAL }

                visibility = if (withActions) VISIBLE else GONE

                setSize(dp(24f))
                setColor(
                    Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_radioBackgroundChecked)
                )
            })
        })

        if (withActions) {
            addView(DividerCell(context).apply { setPadding(dp(8f), dp(8f), dp(8f), dp(8f)) })

            addView(LinearLayout(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )

                gravity = Gravity.CENTER_VERTICAL
                orientation = HORIZONTAL

                addView(shareButton, LayoutParams(dp(40f), dp(40f)))
                addView(editButton, LayoutParams(dp(40f), dp(40f)))
                addView(inheritButton, LayoutParams(dp(40f), dp(40f)))
                addView(View(context), LayoutParams(0, 0, 1f))
                addView(deleteButton, LayoutParams(dp(40f), dp(40f)))
            })
        }
    }
}
