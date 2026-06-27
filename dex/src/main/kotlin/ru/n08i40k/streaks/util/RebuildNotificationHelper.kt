package ru.n08i40k.streaks.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.constants.TranslationKey

class RebuildNotificationHelper(private val translator: Translator) {
    companion object {
        private const val CHANNEL_ID = "tg_streaks_rebuild"
        private const val NOTIFICATION_ID_SINGLE = 7001
        private const val NOTIFICATION_ID_ALL = 7002
        private const val NOTIFICATION_ID_COMPLETE = 7003
    }

    // tracks which notification to overwrite during rate-limit pauses
    private var rateLimitTargetId: Int = NOTIFICATION_ID_SINGLE

    private val manager: NotificationManager
        get() = ApplicationLoader.applicationContext
            .getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            translator.translate(TranslationKey.Rebuild.Notification.CHANNEL_NAME),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
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
        val title = translator.translate(TranslationKey.Rebuild.Notification.STREAK_SINGLE_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Notification.PROGRESS_TEXT,
            mapOf("peer_name" to peerName, "days_checked" to daysChecked.toString())
        )
        notify(NOTIFICATION_ID_SINGLE, progressNotification(title, text, 0, 0, true))
    }

    fun completeSingleStreak(peerName: String, days: Int, revives: Int) {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        val title = translator.translate(TranslationKey.Rebuild.Notification.DONE_STREAK_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Streak.SUMMARY_CHAT,
            mapOf(
                "peer_name" to peerName,
                "days" to days.toString(),
                "revives" to revives.toString()
            )
        )
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, text))
    }

    fun cancelSingleProgress() {
        manager.cancel(NOTIFICATION_ID_SINGLE)
    }

    fun updateAllStreakProgress(index: Int, total: Int, peerName: String, daysChecked: Int) {
        rateLimitTargetId = NOTIFICATION_ID_ALL
        val title = translator.translate(TranslationKey.Rebuild.Notification.STREAK_ALL_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Notification.ALL_GLOBAL_TEXT,
            mapOf(
                "checked_chats" to (index + 1).toString(),
                "total_chats" to total.toString(),
                "peer_name" to peerName,
                "days_checked" to daysChecked.toString(),
            )
        )
        notify(NOTIFICATION_ID_ALL, progressNotification(title, text, index + 1, total, false))
    }

    fun completeAllStreaks(totalChats: Int) {
        manager.cancel(NOTIFICATION_ID_ALL)
        val title = translator.translate(TranslationKey.Rebuild.Notification.DONE_ALL_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Streak.SUMMARY_ALL_CHATS,
            mapOf("checked" to totalChats.toString())
        )
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, text))
    }

    fun cancelAllProgress() {
        manager.cancel(NOTIFICATION_ID_ALL)
    }

    fun updateSinglePetProgress(peerName: String, daysChecked: Int) {
        rateLimitTargetId = NOTIFICATION_ID_SINGLE
        val title = translator.translate(TranslationKey.Rebuild.Notification.PET_SINGLE_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Notification.PROGRESS_TEXT,
            mapOf("peer_name" to peerName, "days_checked" to daysChecked.toString())
        )
        notify(NOTIFICATION_ID_SINGLE, progressNotification(title, text, 0, 0, true))
    }

    fun completeSinglePet(peerName: String) {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        val title = translator.translate(TranslationKey.Rebuild.Notification.DONE_PET_TITLE)
        notify(NOTIFICATION_ID_COMPLETE, doneNotification(title, peerName))
    }

    fun showRateLimitCountdown(peerName: String, remainingMs: Long, totalMs: Long) {
        val totalSec = (totalMs / 1000L).coerceAtLeast(1L).toInt()
        val remainingSec = (remainingMs / 1000L).toInt()
        val progressDone = (totalSec - remainingSec).coerceIn(0, totalSec)
        val title = translator.translate(TranslationKey.Rebuild.Notification.RATE_LIMIT_TITLE)
        val text = translator.translate(
            TranslationKey.Rebuild.Notification.RATE_LIMIT_TEXT,
            mapOf("peer_name" to peerName, "seconds" to remainingSec.toString())
        )
        notify(rateLimitTargetId, progressNotification(title, text, progressDone, totalSec, false))
    }

    fun cancelAll() {
        manager.cancel(NOTIFICATION_ID_SINGLE)
        manager.cancel(NOTIFICATION_ID_ALL)
        manager.cancel(NOTIFICATION_ID_COMPLETE)
    }
}
