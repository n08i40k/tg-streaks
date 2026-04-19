@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import android.graphics.drawable.Drawable
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Cells.UserCell
import org.telegram.ui.Components.AnimatedEmojiDrawable
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue

class UserCellHookBundle : HookBundle(){
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Пользователь в списке участников группы
        after(
            UserCell::class.java.getDeclaredMethod(
                "update",
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as UserCell
            val thisClass = UserCell::class.java

            val dialogId = getFieldValue<Long>(thisClass, thisObject, "dialogId")!!

            if (dialogId < 0)
                return@after

            val nameTextView =
                getFieldValue<SimpleTextView>(thisClass, thisObject, "nameTextView")!!

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                dialogId,
                nameTextView = nameTextView
            )

            val rightDrawable = getFieldValue<Drawable>(nameTextView, "rightDrawable")
            val rightDrawable2 = getFieldValue<Drawable>(nameTextView, "rightDrawable2")

            val emojiStatus =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus"
                )
            val emojiStatus2 =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus2"
                )

            val newEmojiStatus =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus"
                )
            val newEmojiStatus2 =
                getFieldValue<AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable>(
                    thisClass,
                    thisObject,
                    "emojiStatus2"
                )

            if (rightDrawable === emojiStatus)
                nameTextView.rightDrawable = newEmojiStatus
            else if (rightDrawable === emojiStatus2)
                nameTextView.rightDrawable = newEmojiStatus2

            if (rightDrawable2 === emojiStatus)
                nameTextView.rightDrawable2 = newEmojiStatus
            else if (rightDrawable2 === emojiStatus2)
                nameTextView.rightDrawable2 = newEmojiStatus2
        }
    }
}