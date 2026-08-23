package ru.n08i40k.streaks.hook.impl.emoji

import android.content.Context
import android.view.View
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.DialogsActivity
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getAsUnchecked
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.isClientVersionBelow

class DialogCellHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = DialogCell::class.java

        val EMOJI_STATUS = getField(CLASS, "emojiStatus")
        val CURRENT_DIALOG_ID = getField(CLASS, "currentDialogId")

        // отсутствует в клиентах ниже 12.2.6
        val EMOJI_STATUS_VIEW by lazy { getField(CLASS, "emojiStatusView") }
    }

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Чат в списке, нужно ещё увеличить bounds по x, иначе текста не будет
        after(
            DialogCell::class.java.getConstructor(
                DialogsActivity::class.java,
                Context::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java,
                Theme.ResourcesProvider::class.java
            )
        )
        { param ->
            StreakEmoji.encapsulate(
                param.thisObject,
                EMOJI_STATUS,
                null,
                0,
                badgeSlot = StreakEmoji.BadgeSlot.STATUS_OR_NAME,
            )
        }

        // Конструктор чата в списке не имеет его в качестве аргумента, он задаётся после
        after(
            DialogCell::class.java.getDeclaredMethod(
                "buildLayout",
            )
        ) { param ->
            val obj = param.thisObject as DialogCell

            EMOJI_STATUS.getAs<StreakEmoji>(obj)
                ?.setPeerUserId(CURRENT_DIALOG_ID.getLong(obj))
        }

        // Фикс отрисовки текста в местах, где размер view ограничен по x.
        // Например, в списке чатов, где у SwapAnimatedEmojiDrawable есть обёртка в виде View,
        // который жёстко ограничен по x.
        if (!isClientVersionBelow("12.2.6")) {
            after(
                DialogCell::class.java.getDeclaredMethod(
                    "onLayout",
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                )
            ) { param ->
                val obj = param.thisObject as DialogCell

                val emojiStatusView = EMOJI_STATUS_VIEW.getAsUnchecked<View>(obj)
                val emojiStatus = EMOJI_STATUS.getAsUnchecked<SwapAnimatedEmojiDrawable>(obj)

                val height = dp(22f)

                emojiStatusView.layout(
                    0,
                    0,
                    maxOf(height * 4, emojiStatus.intrinsicWidth),
                    height
                )
            }
        }
    }
}