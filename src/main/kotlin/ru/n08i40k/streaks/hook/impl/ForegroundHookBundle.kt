package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook

class ForegroundHookBundle : HookBundle() {
    private var isBackground = false

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        after(LaunchActivity::class.java.getDeclaredMethod("onPause")) {
            isBackground = true
            Plugin.getInstance().guideUiManager.pause()
        }

        after(LaunchActivity::class.java.getDeclaredMethod("onResume")) {
            Plugin.getInstance().guideUiManager.refresh()
            if (!isBackground)
                return@after

            isBackground = false

            // пока плагин был в фоне, апдейты обрабатывал только клиент
            Plugin.getInstance()
                .enqueueUpdatesCheck(UserConfig.selectedAccount, "app foreground")
        }
    }
}
