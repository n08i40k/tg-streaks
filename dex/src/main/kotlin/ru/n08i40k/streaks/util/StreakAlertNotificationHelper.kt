package ru.n08i40k.streaks.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.constants.TranslationKey

class StreakAlertNotificationHelper(private val translator: Translator) {
    companion object {
        private const val CHANNEL_ID = "tg_streaks_alerts"
    }

    private val manager: NotificationManager
        get() = ApplicationLoader.applicationContext
            .getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            translator.translate(TranslationKey.Alert.Notification.CHANNEL_NAME),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    private fun smallIconResId(): Int {
        val context = ApplicationLoader.applicationContext
        val res = context.resources
        val packageName = context.packageName
        return res.getIdentifier("notification", "drawable", packageName)
            .takeIf { it != 0 }
            ?: res.getIdentifier("msg_retry", "drawable", packageName)
                .takeIf { it != 0 }
            ?: context.applicationInfo.icon
    }

    private fun nearDeathNotificationId(peerUserId: Long): Int =
        4_000_000 + (peerUserId % 1_000_000).toInt().let { if (it < 0) -it else it }

    private fun deathNotificationId(peerUserId: Long): Int =
        5_000_000 + (peerUserId % 1_000_000).toInt().let { if (it < 0) -it else it }

    private fun notify(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun showNearDeath(peerUserId: Long, peerName: String, streakLength: Int, timeUntilDeathSeconds: Long) {
        val hours = timeUntilDeathSeconds / 3600
        val minutes = (timeUntilDeathSeconds % 3600) / 60
        val timeStr = if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
        val title = translator.translate(TranslationKey.Alert.Notification.NEAR_DEATH_TITLE)
        val text = translator.translate(
            TranslationKey.Alert.Notification.NEAR_DEATH_TEXT,
            mapOf(
                "peer_name" to peerName,
                "days" to streakLength.toString(),
                "time" to timeStr,
            )
        )
        val notification = Notification.Builder(ApplicationLoader.applicationContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        notify(nearDeathNotificationId(peerUserId), notification)
    }

    fun showDeath(peerUserId: Long, peerName: String, streakLength: Int) {
        val title = translator.translate(TranslationKey.Alert.Notification.DEATH_TITLE)
        val text = translator.translate(
            TranslationKey.Alert.Notification.DEATH_TEXT,
            mapOf(
                "peer_name" to peerName,
                "days" to streakLength.toString(),
            )
        )
        val notification = Notification.Builder(ApplicationLoader.applicationContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        notify(deathNotificationId(peerUserId), notification)
    }

    fun cancelNearDeath(peerUserId: Long) {
        manager.cancel(nearDeathNotificationId(peerUserId))
    }

    fun cancelDeath(peerUserId: Long) {
        manager.cancel(deathNotificationId(peerUserId))
    }
}
