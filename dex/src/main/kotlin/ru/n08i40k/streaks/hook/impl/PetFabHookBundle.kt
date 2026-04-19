package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ChatActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook

class PetFabHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        after(
            ChatActivity::class.java.getDeclaredMethod("onResume")
        ) { Plugin.getInstance().petUiManager.scheduleFabRefreshForOpenChat() }

        after(
            ChatActivity::class.java.getDeclaredMethod("onPause")
        ) { AndroidUtilities.runOnUIThread { Plugin.getInstance().petUiManager.dismissFab() } }
    }
}
