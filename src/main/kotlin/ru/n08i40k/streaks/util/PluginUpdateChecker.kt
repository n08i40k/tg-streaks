package ru.n08i40k.streaks.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.constants.Url
import ru.n08i40k.streaks.i18n.Strings
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

class PluginUpdateChecker(
    private val scope: CoroutineScope,
    private val currentVersion: () -> String?,
) {
    companion object {
        private val TIMEOUT = 6.seconds
        private val BULLETIN_DURATION = 5.seconds
    }

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true)
            return

        job = scope.launch {
            try {
                check()
            } catch (e: Exception) {
                Logger.info("Plugin update check failed: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun check() {
        val current = normalize(currentVersion())
        val latestTag = fetchLatestReleaseTag()

        if (latestTag == null) {
            Logger.info("Update check skipped: latest release tag_name is missing")
            return
        }

        val latest = normalize(latestTag)

        if (latest.isEmpty() || latest == current) {
            Logger.info("Plugin is up to date: $current")
            return
        }

        Logger.info("Plugin update available: current=$current, latest=$latestTag")
        showBulletin(current, latestTag)
    }

    private fun fetchLatestReleaseTag(): String? {
        val connection = URL(Url.Repo.LATEST_RELEASE_API).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = TIMEOUT.inWholeMilliseconds.toInt()
            connection.readTimeout = TIMEOUT.inWholeMilliseconds.toInt()
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            if (connection.responseCode !in 200..299)
                throw IllegalStateException("GitHub API responded with ${connection.responseCode}")

            val body = connection
                .inputStream
                .bufferedReader()
                .use { it.readText() }

            return JSONObject(body)
                .optString("tag_name")
                .trim()
                .takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(version: String?): String =
        version
            .orEmpty()
            .trim()
            .removePrefix("v")
            .removePrefix("V")

    private fun showBulletin(current: String, latest: String) = runOnMainThread {
        val fragment = LaunchActivity.getSafeLastFragment()
            ?.takeIf { BulletinFactory.canShowBulletin(it) }
            ?: return@runOnMainThread

        BulletinFactory.of(fragment)
            .createSimpleBulletin(
                R.raw.ic_download,
                Strings.update_available_message(current, latest),
                Strings.update_available_action(),
            ) {
                Browser.openUrl(
                    fragment.parentActivity ?: return@createSimpleBulletin,
                    Url.Tg.UPDATES
                )
            }
            .setDuration(BULLETIN_DURATION.inWholeMilliseconds.toInt())
            .show()
    }
}
