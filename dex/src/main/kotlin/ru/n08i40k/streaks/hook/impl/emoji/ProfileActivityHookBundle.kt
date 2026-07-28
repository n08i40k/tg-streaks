package ru.n08i40k.streaks.hook.impl.emoji

import android.view.View
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getAsUnchecked
import ru.n08i40k.streaks.util.getField

class ProfileActivityHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = ProfileActivity::class.java

        val USER_ID = getField(CLASS, "userId")
        val NAME_TEXT_VIEW = getField(CLASS, "nameTextView")
        val EMOJI_STATUS_DRAWABLE = getField(CLASS, "emojiStatusDrawable")

        // SimpleTextView
        val RIGHT_DRAWABLE_ON_CLICK_LISTENER =
            getField(SimpleTextView::class.java, "rightDrawableOnClickListener")
    }

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        val streaksController = Plugin.getInstance().streaksController

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

            val nameTextView = NAME_TEXT_VIEW
                .getAsUnchecked<Array<SimpleTextView?>>(thisObject)[1]
                ?: return@after

            val rightDrawableOnClick =
                RIGHT_DRAWABLE_ON_CLICK_LISTENER.getAs<View.OnClickListener>(nameTextView)

            nameTextView.setRightDrawableOnClick { view ->
                val userId = USER_ID.getLong(thisObject)

                val streakViewData = streaksController
                    .getViewData(UserConfig.selectedAccount, userId)

                if (streakViewData == null) {
                    rightDrawableOnClick?.onClick(view)
                    return@setRightDrawableOnClick
                }

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
                EMOJI_STATUS_DRAWABLE,
                1,
                userId,
            ) ?: param.result
        }
    }
}
