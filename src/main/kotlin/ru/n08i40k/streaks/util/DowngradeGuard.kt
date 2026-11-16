package ru.n08i40k.streaks.util

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity
import ru.n08i40k.shim.compat.PluginsControllerCompat
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.Url
import ru.n08i40k.streaks.i18n.Strings
import kotlin.time.Duration.Companion.seconds

internal object DowngradeGuard {
    private const val LAST_LOADED_VERSION_KEY = "last_loaded_version"

    private val DIALOG_RETRY_DELAY = 1.seconds

    fun check(version: String): Boolean {
        val previous = runCatching {
            PluginsControllerCompat.instance
                .getPluginSettingString(Plugin.ID, LAST_LOADED_VERSION_KEY, "")
        }
            .onFailure { Logger.info("Failed to read last loaded plugin version: ${it.message}") }
            .getOrNull()
            .orEmpty()

        if (previous.isEmpty() || !isVersionOlder(version, previous))
            return false

        Logger.info("Plugin was downgraded from $previous to $version: load aborted")
        showDialog(previous, version)

        return true
    }

    private fun parseVersion(version: String): List<Int> =
        version
            .trim()
            .trimStart('v', 'V')
            .split('.')
            .map { chunk -> chunk.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private fun isVersionOlder(version: String, required: String): Boolean {
        val left = parseVersion(version)
        val right = parseVersion(required)

        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }

            if (a != b)
                return a < b
        }

        return false
    }

    private fun showDialog(previous: String, current: String) {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment()

            if (fragment == null) {
                Logger.info("Downgrade dialog deferred: UI context is unavailable")
                scheduleRetry(previous, current)
                return@runOnUIThread
            }

            try {
                val dialog = AlertDialog.Builder(fragment.context)
                    .setTitle(Strings.dialog_downgrade_title())
                    .setMessage(Strings.dialog_downgrade_message(current, previous))
                    .setPositiveButton(Strings.dialog_downgrade_channel()) { _, _ ->
                        val activity = fragment.parentActivity
                            ?: return@setPositiveButton

                        Browser.openUrl(activity, Url.Tg.UPDATES)
                    }
                    .create()
                    .apply {
                        setCancelable(false)
                        setCanceledOnTouchOutside(false)
                    }

                fragment.showDialog(dialog)
            } catch (e: Throwable) {
                Logger.info("Failed to show downgrade dialog: ${e.stackTraceToString()}")
                scheduleRetry(previous, current)
            }
        }
    }

    private fun scheduleRetry(previous: String, current: String) {
        AndroidUtilities.runOnUIThread(
            { showDialog(previous, current) },
            DIALOG_RETRY_DELAY.inWholeMilliseconds,
        )
    }
}
