@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import android.content.Context
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.DialogsActivity
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.isClientVersionBelow

class DialogCellHookBundle : HookBundle() {
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
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                0,
                true
            )
        }

        // Конструктор чата в списке не имеет его в качестве аргумента, он задаётся после
        after(
            DialogCell::class.java.getDeclaredMethod(
                "buildLayout",
            )
        ) { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            val currentDialogId = getFieldValue<Long>(thisClass, thisObject, "currentDialogId")!!

            getFieldValue<StreakEmoji>(
                thisClass,
                thisObject,
                "emojiStatus"
            )?.setPeerUserId(currentDialogId)
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
                val thisObject = param.thisObject as DialogCell
                val thisClass = DialogCell::class.java

                val emojiStatusView =
                    getFieldValue<View>(thisClass, thisObject, "emojiStatusView")!!
                val emojiStatus =
                    getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                        thisClass,
                        thisObject,
                        "emojiStatus"
                    )

                val height = AndroidUtilities.dp(22f)
                emojiStatusView.layout(
                    0,
                    0,
                    maxOf(height * 4, emojiStatus?.intrinsicWidth ?: 0),
                    height
                )
            }
        }
    }
}