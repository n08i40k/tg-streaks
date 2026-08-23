package ru.n08i40k.streaks.hook.impl.emoji

import android.graphics.drawable.Drawable
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import java.lang.reflect.Field

class ProfileActivityHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = ProfileActivity::class.java

        val USER_ID = getField(CLASS, "userId")
        val NAME_TEXT_VIEW = getField(CLASS, "nameTextView")
        val EMOJI_STATUS_DRAWABLE = getField(CLASS, "emojiStatusDrawable")

        // поле клиента, появилось в 12.5.1
        val BADGE_DRAWABLE = try {
            getField(CLASS, "badgeDrawable")
        } catch (_: NoSuchFieldException) {
            null
        }

        val HOST_FIELDS = listOfNotNull(EMOJI_STATUS_DRAWABLE, BADGE_DRAWABLE)
    }

    private fun getDrawable(fragment: ProfileActivity, field: Field): Drawable? =
        (field.get(fragment) as Array<*>)[1] as Drawable?

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

            val nameTextView = NAME_TEXT_VIEW
                .getAs<Array<SimpleTextView?>>(thisObject)
                ?.get(1)
                ?: return@after

            // при пустом статусе клиент отдаёт правому drawable бейдж, а не emojiStatusDrawable
            val hostField = HOST_FIELDS.firstOrNull {
                val drawable = getDrawable(thisObject, it)

                drawable != null
                        && (drawable === nameTextView.rightDrawable
                        || drawable === nameTextView.rightDrawable2)
            } ?: return@after

            val isSecondary = getDrawable(thisObject, hostField) === nameTextView.rightDrawable2

            HOST_FIELDS
                .filter { it !== hostField }
                .forEach { (getDrawable(thisObject, it) as? StreakEmoji)?.setPeerUserId(0L, true) }

            val emoji = StreakEmoji.encapsulate(
                thisObject,
                hostField,
                1,
                userId,
                badgeSlot = StreakEmoji.BadgeSlot.SEPARATE,
                simpleTextView = nameTextView,
            ) ?: return@after

            if (isSecondary) {
                if (nameTextView.rightDrawable2 !== emoji)
                    nameTextView.rightDrawable2 = emoji
            } else if (nameTextView.rightDrawable !== emoji) {
                nameTextView.rightDrawable = emoji
            }
        }
    }
}
