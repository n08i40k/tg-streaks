package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Cells.UserCell
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue

class UserCellHookBundle : HookBundle() {
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

            val currentUser = getFieldValue<TLRPC.User>(thisClass, thisObject, "currentObject")
                ?: return@after

            val nameTextView =
                getFieldValue<SimpleTextView>(thisClass, thisObject, "nameTextView")!!

            val emoji = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                currentUser.id,
                simpleTextView = nameTextView
            )

            nameTextView.rightDrawable = emoji
        }
    }
}