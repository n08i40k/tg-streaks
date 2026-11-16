package ru.n08i40k.streaks.hook.impl

import android.content.Intent
import org.telegram.ui.LaunchActivity
import ru.n08i40k.shim.hooks.HooksScope
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle

class ActivityResultHookBundle : HookBundle {
    override fun HooksScope.install() {
        after(
            LaunchActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            )
        ) { param ->
            Plugin.getInstance()
                .databaseMaintenance
                .onActivityResult(
                    param.args[0] as Int,
                    param.args[1] as Int,
                    param.args[2] as Intent?,
                )
        }
    }
}
