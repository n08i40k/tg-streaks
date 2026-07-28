package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.util.getAsUnchecked
import ru.n08i40k.streaks.util.getField

class PremiumPreviewBottomSheetHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = PremiumPreviewBottomSheet::class.java

        val USER = getField(CLASS, "user")
    }

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

            val user = USER.getAsUnchecked<TLRPC.User>(dialog)

            val streakViewData = Plugin.getInstance().streaksController
                .getViewData(
                    UserConfig.selectedAccount,
                    user.id
                ) ?: return@before

            param.args[0] = StreakInfoBottomSheet(dialog, user, streakViewData)
        }
    }
}