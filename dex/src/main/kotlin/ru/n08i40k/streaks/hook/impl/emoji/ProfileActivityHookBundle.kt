@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
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
                "updateProfileData",
                Boolean::class.java,
            )
        ) { param ->
            val thisObject = param.thisObject as ProfileActivity
            val thisClass = ProfileActivity::class.java

            val userId = getFieldValue<Long>(thisClass, thisObject, "userId")!!

            if (userId < 0)
                return@after

            val nameTextView =
                getFieldValue<Array<SimpleTextView>>(
                    thisClass,
                    thisObject,
                    "nameTextView"
                )!![1]

            nameTextView.setRightDrawableOnClick {
                val dialog = PremiumPreviewBottomSheet(
                    thisObject,
                    UserConfig.selectedAccount,
                    MessagesController.getInstance(UserConfig.selectedAccount).getUser(userId),
                    thisObject.resourceProvider
                )

                thisObject.showDialog(dialog)
            }

            param.result = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                1,
                userId,
            ) ?: param.result
        }
    }
}