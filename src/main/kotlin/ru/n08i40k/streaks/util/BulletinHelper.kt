package ru.n08i40k.streaks.util

import androidx.annotation.AnyThread
import androidx.annotation.RawRes
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

object BulletinHelper {
    @AnyThread
    fun info(message: CharSequence) = show(R.raw.info, message)

    @AnyThread
    fun error(message: CharSequence) = show(R.raw.error, message)

    @AnyThread
    fun success(message: CharSequence) = show(R.raw.contact_check, message)

    /**
     * @param icon lottie animation from `R.raw`.
     */
    @AnyThread
    fun show(@RawRes icon: Int, message: CharSequence) = runOnMainThread {
        val fragment = LaunchActivity.getSafeLastFragment()
            ?.takeIf { BulletinFactory.canShowBulletin(it) }
            ?: return@runOnMainThread

        BulletinFactory.of(fragment)
            .createSimpleBulletin(icon, message)
            .show()
    }
}
