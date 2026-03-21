@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS")

package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

class BulletinHelper(private val translator: Translator) {
    companion object {
        fun show(icon: String?, message: String) {
            AndroidUtilities.runOnUIThread {
                val fragment = LaunchActivity.getSafeLastFragment()
                    ?.takeIf { BulletinFactory.canShowBulletin(it) }
                    ?: return@runOnUIThread

                val bulletin = BulletinFactory.of(fragment).let { factory ->
                    if (icon.isNullOrBlank()) {
                        factory.createSimpleBulletin(message, "")
                    } else {
                        val drawableId = ApplicationLoader.applicationContext.resources
                            .getIdentifier(
                                icon,
                                "drawable",
                                ApplicationLoader.applicationContext.packageName
                            )

                        if (drawableId != 0) {
                            factory.createSimpleBulletin(drawableId, message)
                        } else {
                            factory.createEmojiBulletin(icon, message)
                        }
                    }
                }

                bulletin.show()
            }
        }
    }

    fun show(icon: String?, message: String) =
        BulletinHelper.show(icon, message)

    fun showTranslated(key: String, icon: String? = null) =
        show(icon, translator.translate(key))

    fun showTranslated(key: String, replacements: Map<String, String>, icon: String? = null) =
        show(icon, translator.translate(key, replacements))
}