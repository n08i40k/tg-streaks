package ru.n08i40k.streaks.controller

import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.ScheduledStreakPopup
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.database.dao.ScheduledStreakPopupDao
import ru.n08i40k.streaks.ui.upgrade.StreakUpgrade
import ru.n08i40k.streaks.ui.upgrade.StreakUpgradeDialog
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.concurrent.atomic.AtomicBoolean

class StreakPopupController(
    private val dao: ScheduledStreakPopupDao,
) {
    companion object {
        private const val POPUP_KIND_CREATED = "created"
        private const val POPUP_KIND_UPGRADED = "upgraded"
        private const val MAX_QUEUE_SIZE_PER_CHAT = 5
    }

    private val isShowing = AtomicBoolean(false)

    suspend fun enqueueForTransition(
        accountId: Int,
        peerUserId: Long,
        before: Streak?,
        after: Streak?
    ) {
        if (accountId < 0
            || peerUserId <= 0L
            || after == null
            || after.ended
            || after.length < 3
        )
            return

        val (key, fromLevel) = when {
            before == null || before.length < 3 -> {
                val streakLevelPack = Plugin.getInstance()
                    .streakLevelPacksController
                    .getCurrent()

                val key = "$POPUP_KIND_CREATED:$accountId:$peerUserId:${after.level.length}"
                val value = (streakLevelPack.findPrevious(after.level) ?: streakLevelPack.getCold())

                key to value
            }

            !before.ended && after.level > before.level ->
                "$POPUP_KIND_UPGRADED:$accountId:$peerUserId:${after.level.length}" to before.level

            else -> return
        }

        val insertedId = dao.insert(
            ScheduledStreakPopup(
                accountId = accountId,
                peerUserId = peerUserId,
                fromLevelId = fromLevel.id,
                toLevelId = after.level.id,
                dedupeKey = key,
                scheduledAt = System.currentTimeMillis(),
            )
        )

        if (insertedId == -1L)
            return

        dao.trimRelationQueue(accountId, peerUserId, MAX_QUEUE_SIZE_PER_CHAT)

        val relation = resolveOpenChatRelation()

        if (relation?.accountId == accountId && relation.peerUserId == peerUserId)
            flushRelation(accountId, peerUserId)
    }


    suspend fun flushCurrentChat() {
        val relation = resolveOpenChatRelation() ?: return
        flushRelation(relation.accountId, relation.peerUserId)
    }

    private suspend fun flushRelation(accountId: Int, peerUserId: Long) {
        if (!isShowing.compareAndSet(false, true)) {
            return
        }

        val popup = dao.findFirstByRelation(accountId, peerUserId)
        if (popup == null) {
            isShowing.set(false)
            return
        }

        val shown = showPopup(popup) {
            Plugin.getInstance()
                .enqueueTask("delete pending popup from database") {
                    dao.delete(popup)
                    isShowing.set(false)
                    flushCurrentChat()
                }
        }

        if (!shown) {
            isShowing.set(false)
        }
    }

    private fun showPopup(
        popup: ScheduledStreakPopup,
        onDismiss: () -> Unit,
    ): Boolean {
        val relation = resolveOpenChatRelation()
            ?: return false

        if (relation.accountId != popup.accountId
            || relation.peerUserId != popup.peerUserId
        )
            return false

        val context = resolvePopupContext()
            ?: return false

        val streakLevelPack = Plugin.getInstance()
            .streakLevelPacksController
            .getCurrent()

        val from = streakLevelPack.findById(popup.fromLevelId)
        val to = streakLevelPack.findById(popup.toLevelId)

        if (from == null || to == null) {
            onDismiss()
            return true
        }

        runOnMainThread {
            val result = Logger.tryOrFatal("show streak popup") {
                val peer = MessagesController.getInstance(popup.accountId).getUser(popup.peerUserId)

                StreakUpgradeDialog(context, StreakUpgrade.of(from, to, peer, popup.accountId))
                    .apply {
                        setOnDismissListener { onDismiss() }
                        show()
                    }
            }

            if (result == null)
                onDismiss()
        }

        return true
    }

    private fun resolvePopupContext(): android.content.Context? {
        val fragment = LaunchActivity.getSafeLastFragment()

        return try {
            fragment?.parentActivity
                ?: fragment?.context
                ?: org.telegram.messenger.ApplicationLoader.applicationContext
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveOpenChatRelation(): OpenChatRelation? {
        val fragment = LaunchActivity.getSafeLastFragment() as? ChatActivity
            ?: return null

        val dialogId = fragment.dialogId

        if (dialogId <= 0L)
            return null

        return OpenChatRelation(
            accountId = UserConfig.selectedAccount,
            peerUserId = dialogId
        )
    }

    private data class OpenChatRelation(
        val accountId: Int,
        val peerUserId: Long,
    )
}
