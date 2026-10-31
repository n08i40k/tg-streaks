package ru.n08i40k.streaks.ui.upgrade

import android.graphics.Typeface
import androidx.annotation.MainThread
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.util.Logger

internal object StreakUpgradeFont {
    private const val RESOURCE = "upgrade-scene/streak-digits.ttf"

    private var resolved = false
    private var typeface: Typeface? = null

    @MainThread
    fun get(): Typeface? {
        if (!resolved) {
            resolved = true
            typeface = load()
        }
        return typeface
    }

    private fun load(): Typeface? {
        val file = Plugin.getInstance().resourcesProvider.resolveResource(RESOURCE)
        if (file == null) {
            Logger.info("Upgrade counter font $RESOURCE is missing, keeping the system font")
            return null
        }
        return try {
            Typeface.createFromFile(file)
        } catch (error: RuntimeException) {
            Logger.info("Upgrade counter font $RESOURCE is unreadable, keeping the system font: ${error.message}")
            null
        }
    }
}
