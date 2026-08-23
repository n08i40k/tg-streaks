package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.AccountTaskExecutor

class AccountSwitchHookBundle : HookBundle() {
    var currentAccountId: Int = 0

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        currentAccountId = UserConfig.selectedAccount

        after(
            LaunchActivity::class.java.declaredMethods
                .filter { it.name == "switchToAccount" }
                .maxByOrNull { it.parameterCount }!!
        ) {
            val plugin = Plugin.getInstance()
            val accountId = UserConfig.selectedAccount

            if (currentAccountId == accountId)
                return@after

            currentAccountId = accountId

            AccountTaskExecutor.stopAll(accountId)
            plugin.enqueueAccountInitializationTasks(accountId, "account switch")
        }
    }
}