package ru.n08i40k.streaks.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.i18n.Strings

object CheckNotificationHelper : EjectNotifier.Delegate {
    init {
        EjectNotifier.subscribe(this)
    }

    private const val CHANNEL_ID = "tg_streaks_check"
    private const val NOTIFICATION_ID_CHECK = 7004

    private var canBeUsed = false

    private val manager: NotificationManager
        get() = ApplicationLoader.applicationContext
            .getSystemService(NotificationManager::class.java)

    fun createChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                Strings.check_notification_channel_name(),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }

            manager.createNotificationChannel(channel)

            canBeUsed = true
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("DiscouragedApi")
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

    private fun progressNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
    ): Notification =
        Notification.Builder(ApplicationLoader.applicationContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId())
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()

    private fun notify(notification: Notification) {
        if (!canBeUsed) return
        if (!manager.areNotificationsEnabled()) return

        try {
            manager.notify(NOTIFICATION_ID_CHECK, notification)
        } catch (_: SecurityException) {
        }
    }

    fun showRateLimitCountdown(remainingMs: Long, totalMs: Long) {
        val totalSec = (totalMs / 1000L).coerceAtLeast(1L).toInt()
        val remainingSec = (remainingMs / 1000L).toInt()
        val progressDone = (totalSec - remainingSec).coerceIn(0, totalSec)
        val title = Strings.check_notification_rate_limit_title()
        val text = Strings.check_notification_rate_limit_text(remainingSec)
        notify(progressNotification(title, text, progressDone, totalSec))
    }

    fun updateCheckProgress(
        index: Int,
        total: Int,
        peerName: String,
        daysChecked: Int,
        totalDays: Int,
    ) {
        val title = Strings.check_notification_title()
        val text = Strings.check_notification_text(
            index + 1,
            daysChecked,
            peerName,
            total,
            totalDays,
        )
        notify(progressNotification(title, text, daysChecked, totalDays))
    }

    fun cancelCheckProgress() {
        if (!canBeUsed)
            return

        manager.cancel(NOTIFICATION_ID_CHECK)
    }

    fun cancelRateLimitNotification() {
        if (!canBeUsed)
            return

        manager.cancel(NOTIFICATION_ID_CHECK)
    }

    fun cancelAll() {
        if (!canBeUsed)
            return

        manager.cancel(NOTIFICATION_ID_CHECK)
    }

    override fun onEject() {
        cancelAll()
    }
}
