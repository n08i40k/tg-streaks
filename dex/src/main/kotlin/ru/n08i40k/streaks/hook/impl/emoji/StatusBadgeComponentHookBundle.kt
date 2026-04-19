@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import android.view.View
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.StatusBadgeComponent
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue

class StatusBadgeComponentHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Эмодзи пользователя в просмотрах сторисов (мб ещё где-то, но я не видел)
        after(
            StatusBadgeComponent::class.java.getDeclaredConstructor(
                View::class.java,
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as StatusBadgeComponent
            val thisClass = StatusBadgeComponent::class.java

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "statusDrawable"),
                null,
                0,
                true
            )
        }

        after(
            StatusBadgeComponent::class.java.getDeclaredMethod(
                "updateDrawable",
                TLRPC.User::class.java,
                TLRPC.Chat::class.java,
                Int::class.java,
                Boolean::class.java
            )
        )
        { param ->
            val thisObject = param.thisObject as StatusBadgeComponent
            val thisClass = StatusBadgeComponent::class.java

            val user = param.args[0] as? TLRPC.User
                ?: return@after

            // update user id
            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "statusDrawable"),
                null,
                user.id,
                true,
            )
        }
    }
}