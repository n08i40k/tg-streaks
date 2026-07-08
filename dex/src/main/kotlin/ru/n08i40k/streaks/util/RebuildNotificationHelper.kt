package ru.n08i40k.streaks.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.event.eject.EjectNotifier

object RebuildNotificationHelper : EjectNotifier.Delegate() {
    private const val CHANNEL_ID = "tg_streaks_rebuild"
    private const val NOTIFICATION_ID_SINGLE = 7001
    private const val NOTIFICATION_ID_ALL = 7002
    private const val NOTIFICATION_ID_COMPLETE = 7003
    private const val NOTIFICATION_ID_CHECK = 7004

    // tracks which notification to overwrite during rate-limit pauses
    private var rateLimitTargetId: Int = NOTIFICATION_ID_SINGLE

    private val manager: NotificationManager
        get() = ApplicationLoader.applicationContext
            .getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            Strings.rebuild_notification_channel_name(),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannel(channel)
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
        indeterminate: Boolean,
    ): Notification =
        Notification.Builder(ApplicationLoader.applicationContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId())
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()

    private fun doneNotification(title: String, text: String): Notification =
        Notification.Builder(ApplicationLoader.applicationContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private fun notify(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun updateSingleStreakProgress(peerName: String, daysChecked: Int) {
        rateLimitTargetId = NOTIFICATION_ID_SINGLE
        val title = Strings.rebuild_notification_streak_single_title()
        val text = Strings.rebuild_notification_progress_text(daysChecked, peerName)
        notify(NOTIFICATION_ID_SINGLE, progressNotification(title, text, 0, 0, true))
    }

    fun completeSingleStreak(peerName: String, days: Int, revives: Int) {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        val title = Strings.rebuild_notification_done_streak_title()
        val text = Strings.rebuild_streak_summary_chat(days, peerName, revives)
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, text))
    }

    fun cancelSingleProgress() {
        manager.cancel(NOTIFICATION_ID_SINGLE)
    }

    fun updateAllStreakProgress(index: Int, total: Int, peerName: String, daysChecked: Int) {
        rateLimitTargetId = NOTIFICATION_ID_ALL
        val title = Strings.rebuild_notification_streak_all_title()
        val text = Strings.rebuild_notification_all_global_text(
            index + 1,
            daysChecked,
            peerName,
            total,
        )
        notify(NOTIFICATION_ID_ALL, progressNotification(title, text, index + 1, total, false))
    }

    fun completeAllStreaks(totalChats: Int) {
        manager.cancel(NOTIFICATION_ID_ALL)
        val title = Strings.rebuild_notification_done_all_title()
        val text = Strings.rebuild_streak_summary_all_chats(totalChats)
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, text))
    }

    fun cancelAllProgress() {
        manager.cancel(NOTIFICATION_ID_ALL)
    }

    fun updateSinglePetProgress(peerName: String, daysChecked: Int) {
        rateLimitTargetId = NOTIFICATION_ID_SINGLE
        val title = Strings.rebuild_notification_pet_single_title()
        val text = Strings.rebuild_notification_progress_text(daysChecked, peerName)
        notify(NOTIFICATION_ID_SINGLE, progressNotification(title, text, 0, 0, true))
    }

    fun completeSinglePet(peerName: String) {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        val title = Strings.rebuild_notification_done_pet_title()
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, peerName))
    }

    fun showRateLimitCountdown(peerName: String, remainingMs: Long, totalMs: Long) {
        val totalSec = (totalMs / 1000L).coerceAtLeast(1L).toInt()
        val remainingSec = (remainingMs / 1000L).toInt()
        val progressDone = (totalSec - remainingSec).coerceIn(0, totalSec)
        val title = Strings.rebuild_notification_rate_limit_title()
        val text = Strings.rebuild_notification_rate_limit_text(peerName, remainingSec)
        notify(rateLimitTargetId, progressNotification(title, text, progressDone, totalSec, false))
    }

    fun beginCheckNotification() {
        rateLimitTargetId = NOTIFICATION_ID_CHECK
    }

    fun updateCheckProgress(
        index: Int,
        total: Int,
        peerName: String,
        daysChecked: Int,
        totalDays: Int,
    ) {
        rateLimitTargetId = NOTIFICATION_ID_CHECK
        val title = Strings.check_notification_title()
        val text = Strings.check_notification_text(
            index + 1,
            daysChecked,
            peerName,
            total,
            totalDays,
        )
        notify(
            NOTIFICATION_ID_CHECK,
            progressNotification(title, text, daysChecked, totalDays, false)
        )
    }

    fun cancelCheckProgress() {
        manager.cancel(NOTIFICATION_ID_CHECK)
    }

    fun cancelRateLimitNotification() {
        manager.cancel(rateLimitTargetId)
    }

    fun cancelAll() {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        manager.cancel(NOTIFICATION_ID_ALL)
        manager.cancel(NOTIFICATION_ID_COMPLETE)
        manager.cancel(NOTIFICATION_ID_CHECK)
    }

    override fun onEject() {
        cancelAll()
    }
}
