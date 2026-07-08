package ru.n08i40k.streaks.util

import android.annotation.SuppressLint
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

object BulletinHelper {
    @SuppressLint("DiscouragedApi")
    fun show(message: String, icon: String? = null) {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment()
                ?.takeIf { BulletinFactory.canShowBulletin(it) }
                ?: return@runOnUIThread

            val bulletin = BulletinFactory.of(fragment).let { factory ->
                if (icon.isNullOrBlank())
                    return@let factory.createSimpleBulletin(message, "")

                val context = ApplicationLoader.applicationContext

                val drawableId = context.resources
                    .getIdentifier(icon, "drawable", context.packageName)

                if (drawableId != 0)
                    factory.createSimpleBulletin(drawableId, message)
                else
                    factory.createEmojiBulletin(icon, message)
            }

            bulletin.show()
        }
    }
}