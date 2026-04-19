@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.tgnet.TLRPC
import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue

class ProfileActivityHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Профиль пользователя
        after(
            ProfileActivity::class.java.getDeclaredMethod(
                "getEmojiStatusDrawable",
                TLRPC.EmojiStatus::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ProfileActivity
            val thisClass = ProfileActivity::class.java

            val userId = getFieldValue<Long>(thisObject, "userId")!!

            if (userId < 0)
                return@after

            param.result = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                param.args[3] as Int,
                userId
            ) ?: param.result
        }
    }
}