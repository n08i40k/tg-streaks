package ru.n08i40k.streaks.hook

import ru.n08i40k.shim.hooks.HooksScope
import ru.n08i40k.streaks.hook.impl.AccountSwitchHookBundle
import ru.n08i40k.streaks.hook.impl.ActivityResultHookBundle
import ru.n08i40k.streaks.hook.impl.ForegroundHookBundle
import ru.n08i40k.streaks.hook.impl.GuideLinksHookBundle
import ru.n08i40k.streaks.hook.impl.PetFabHookBundle
import ru.n08i40k.streaks.hook.impl.ServiceMessagesHookBundle
import ru.n08i40k.streaks.hook.impl.UpdatesHookBundle

val HOOK_BUNDLES: Array<HookBundle> = arrayOf(
    AccountSwitchHookBundle(),
    ActivityResultHookBundle(),
    ForegroundHookBundle(),
    GuideLinksHookBundle(),
    PetFabHookBundle(),
    ServiceMessagesHookBundle(),
    UpdatesHookBundle(),
)

fun interface HookBundle {
    fun HooksScope.install()
}