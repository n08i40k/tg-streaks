package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Cells.UserCell
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getAsUnchecked
import ru.n08i40k.streaks.util.getField

class UserCellHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = UserCell::class.java

        val CURRENT_OBJECT = getField(CLASS, "currentObject")
        val NAME_TEXT_VIEW = getField(CLASS, "nameTextView")
        val EMOJI_STATUS = getField(CLASS, "emojiStatus")
    }

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

            val currentUser = CURRENT_OBJECT.getAs<TLRPC.User>(thisObject)
                ?: return@after

            val nameTextView = NAME_TEXT_VIEW.getAsUnchecked<SimpleTextView>(thisObject)

            val emoji = StreakEmoji.encapsulate(
                thisObject,
                EMOJI_STATUS,
                null,
                currentUser.id,
                simpleTextView = nameTextView
            )

            nameTextView.rightDrawable = emoji
        }
    }
}
