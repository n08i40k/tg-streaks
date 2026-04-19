@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.util.getFieldValue

class PremiumPreviewBottomSheetHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Хук отображения диалоговых окон для замены PremiumPreviewBottomSheet
        before(
            BaseFragment::class.java
                .getDeclaredMethods()
                .filter { it.name == "showDialog" }
                .sortedByDescending { it.parameterCount }[0]
        ) { param ->
            val dialog = param.args[0] as? PremiumPreviewBottomSheet
                ?: return@before

            val user = getFieldValue<TLRPC.User>(
                PremiumPreviewBottomSheet::class.java,
                dialog,
                "user"
            )!!

            val streakViewData = Plugin.getInstance().streaksController
                .getViewDataBlocking(
                    UserConfig.selectedAccount,
                    user.id
                ) ?: return@before

            param.args[0] = StreakInfoBottomSheet(dialog, user, streakViewData)
        }
    }
}