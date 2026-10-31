package ru.n08i40k.streaks.hook.impl

import android.net.Uri
import org.telegram.messenger.browser.Browser
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.guide.StreakGuideLinks
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.runOnMainThread

/** Opens [StreakGuideLinks] instead of passing them to Telegram, which does not know them. */
class GuideLinksHookBundle : HookBundle() {
    override fun inject(before: InstallHook, after: InstallHook) {
        // Every other openUrl overload delegates to the full one.
        val openUrl = Browser::class.java.declaredMethods.single {
            it.name == "openUrl" && it.parameterCount == 10 && it.parameterTypes[1] == Uri::class.java
        }

        before(openUrl) { param ->
            val id = (param.args[1] as? Uri)?.let(StreakGuideLinks::parse)
                ?: return@before

            param.result = null
            runOnMainThread { Plugin.getInstance().guideUiManager.show(id.ifEmpty { null }) }
        }
    }
}
