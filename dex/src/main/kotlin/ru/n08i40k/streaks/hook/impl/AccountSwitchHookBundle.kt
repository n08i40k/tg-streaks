package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook

class AccountSwitchHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        after(
            LaunchActivity::class.java.declaredMethods
                .filter { it.name == "switchToAccount" }
                .maxByOrNull { it.parameterCount }!!
        ) {
            val plugin = Plugin.getInstance()
            val accountId = UserConfig.selectedAccount

            plugin.accountTaskRunnerRegistry.stopAll(accountId)
            plugin.enqueueAccountInitializationTasks(accountId, "account switch")
        }
    }
}