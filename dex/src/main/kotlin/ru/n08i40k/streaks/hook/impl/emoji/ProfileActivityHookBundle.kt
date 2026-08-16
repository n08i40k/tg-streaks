package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField

class ProfileActivityHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = ProfileActivity::class.java

        val USER_ID = getField(CLASS, "userId")
        val EMOJI_STATUS_DRAWABLE = getField(CLASS, "emojiStatusDrawable")
    }

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Профиль пользователя
        after(
            ProfileActivity::class.java.getDeclaredMethod(
                "updateProfileData",
                Boolean::class.java,
            )
        ) { param ->
            val thisObject = param.thisObject as ProfileActivity

            val userId = USER_ID.getLong(thisObject)

            if (userId < 0)
                return@after

            param.result = StreakEmoji.encapsulate(
                thisObject,
                EMOJI_STATUS_DRAWABLE,
                1,
                userId,
            ) ?: param.result
        }
    }
}
