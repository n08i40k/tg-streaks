@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ProfileSearchCell
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField

class ProfileSearchCellHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Чат в списке результатов поиска
        after(
            // additional argument at index 1 for badgeDTO
            ProfileSearchCell::class.java.declaredMethods
                .find { it.name == "updateStatus" }!!
        )
        { param ->
            val thisObject = param.thisObject as ProfileSearchCell
            val thisClass = ProfileSearchCell::class.java

            val user = param.args[2] as? TLRPC.User
                ?: return@after

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "statusDrawable"),
                null,
                user.id,
                true
            )
        }
    }
}