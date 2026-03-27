@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.chat_history_fetcher.CachedChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.RemoteChatHistoryFetcher
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakRevive
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.PeerType
import ru.n08i40k.streaks.extension.getPeerType
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.isPeerValidOrBot
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.toEpochSecondUtc
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.Logger
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.comparisons.compareBy

@OptIn(DelicateCoroutinesApi::class)
class StreaksController(
    private val db: PluginDatabase,
    private val logger: Logger,
    resourcesProvider: ResourcesProvider,
) {
    companion object {
        private const val MIN_VISIBLE_STREAK_LENGTH = 3
    }

    data class HandleUpdateResult(
        val changed: Boolean,
        val created: Boolean,
    )

    data class UiSyncTarget(
        val accountId: Int,
        val peerUserId: Long,
    )

    data class RebuildAllResult(
        val totalChats: Int,
        val uiSyncTargets: List<UiSyncTarget>,
    )

    data class RebuildProgress(
        val user: TLRPC.User,
        val daysChecked: Int,
    ) {
        fun showBulletin() {
            val plugin = Plugin.getInstance()

            val message = plugin.translator.translate(
                TranslationKey.FORCE_CHECK_DAY_PROGRESS_CHAT,
                mapOf(
                    "peer_name" to user.label,
                    "days_checked" to daysChecked.toString(),
                )
            )

            AndroidUtilities.runOnUIThread {
                org.telegram.ui.Components.Bulletin.hideVisible()
                plugin.bulletinHelper.show("msg_retry", message)
            }
        }
    }

    private data class OriginalUserState(
        val premium: Boolean,
        val emojiStatus: TLRPC.EmojiStatus?,
    )

    private val rebuildLock = AtomicBoolean(false)
    private val originalUserStates = LinkedHashMap<Long, OriginalUserState>()

    private val cachedFetcher: ChatHistoryFetcher = CachedChatHistoryFetcher()
    private val remoteFetcher: ChatHistoryFetcher = RemoteChatHistoryFetcher()
    private val serviceMessagesController = ServiceMessagesController()
    private val streakPopupController = StreakPopupController(db, resourcesProvider)

    private val dao = db.streakDao()
    private val reviveDao = db.streakReviveDao()

    fun isRebuildRunning(): Boolean =
        rebuildLock.get()

    private fun buildStreak(
        ownerUserId: Long,
        peerUserId: Long,
        length: Int,
        updateFromOwnerAt: LocalDate,
        updateFromPeerAt: LocalDate,
        revivesCount: Int = 0,
    ): Streak {
        val minUpdateAt = minOf(updateFromOwnerAt, updateFromPeerAt, compareBy { it.toEpochDay() })
        val createdAt =
            minUpdateAt.minusDays((length + revivesCount - 1).toLong().coerceAtLeast(0L))

        return Streak(
            ownerUserId,
            peerUserId,
            createdAt,
            updateFromOwnerAt,
            updateFromPeerAt,
            revivesCount,
        )
    }

    private fun isVisibleLength(length: Int): Boolean =
        length >= MIN_VISIBLE_STREAK_LENGTH

    private fun resolveDialogId(dialog: TLRPC.Dialog): Long {
        var dialogId = dialog.id

        if (dialogId == 0L) {
            try {
                DialogObject.initDialog(dialog)
                dialogId = dialog.id
            } catch (_: Throwable) {
                dialogId = 0L
            }
        }

        if (dialogId == 0L) {
            dialogId = dialog.peer?.let { peer ->
                try {
                    DialogObject.getPeerDialogId(peer)
                } catch (_: Throwable) {
                    0L
                }
            } ?: 0L
        }

        return dialogId
    }

    private fun collectEligiblePrivateUsers(accountId: Int): List<TLRPC.User> {
        val messagesController = MessagesController.getInstance(accountId)
        val usersById = LinkedHashMap<Long, TLRPC.User>()

        messagesController.getDialogs(0)
            ?.filterNotNull()
            ?.forEach { dialog ->
                val dialogId = resolveDialogId(dialog)

                if (!DialogObject.isUserDialog(dialogId))
                    return@forEach

                val user = messagesController.getUser(dialogId) ?: return@forEach

                if (!isPeerValid(user))
                    return@forEach

                usersById[user.id] = user
            }

        return usersById.values.toList()
    }

    // I consider streak as living entity in plugin code;
    // it would be better if I considered only streak-pet this way, but why not
    private enum class Action {
        GROW,
        KILL_BY_OWNER,
        KILL_BY_PEER,
        KILL,
        REVIVE,
    }

    private suspend fun fetchStreakActionForDay(
        accountId: Int,
        peer: TLRPC.User,
        day: LocalDate,
        revives: Set<LocalDate>,
        untilRevive: Boolean
    ): Action {
        if (revives.contains(day))
            return Action.REVIVE

        when (val status =
            cachedFetcher.fetchActivity(accountId, peer.id, day, untilRevive)) {
            is ChatHistoryFetcher.Status.FromBoth -> return if (status.wasRevived) Action.REVIVE else Action.GROW
            is ChatHistoryFetcher.Status.FromOwner -> if (status.wasRevived) return Action.REVIVE
            is ChatHistoryFetcher.Status.FromPeer -> if (status.wasRevived) return Action.REVIVE
            else -> {}
        }

        return when (val status =
            remoteFetcher.fetchActivity(accountId, peer.id, day, untilRevive)) {
            is ChatHistoryFetcher.Status.FromBoth -> if (status.wasRevived) Action.REVIVE else Action.GROW
            is ChatHistoryFetcher.Status.FromOwner -> if (status.wasRevived) Action.REVIVE else Action.KILL_BY_PEER
            is ChatHistoryFetcher.Status.FromPeer -> if (status.wasRevived) Action.REVIVE else Action.KILL_BY_OWNER
            is ChatHistoryFetcher.Status.NoActivity -> Action.KILL
        }
    }

    suspend fun rebuild(
        accountId: Int,
        peer: TLRPC.User,
        revives: Set<LocalDate>? = null,
        ignoreLock: Boolean = false,
        sendServiceMessages: Boolean = false,
        onProgressUpdate: (progress: RebuildProgress) -> Unit,
    ) {
        if (!ignoreLock && !rebuildLock.compareAndSet(false, true)) {
            logger.info("Unable to rebuild peer $accountId:${peer.id} because another rebuild is already running")
            return
        }

        try {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val peerUserId = peer.id

            val revives = (revives ?: reviveDao.findByRelation(ownerUserId, peerUserId)
                .map { it.revivedAt }).toMutableSet()

            val startDay = LocalDate.now()
            var currentDay = LocalDate.now()

            var startDayIsFrozen = false

            while (true) {
                val checkedDay = currentDay
                val action = fetchStreakActionForDay(accountId, peer, checkedDay, revives, false)
                val progress = RebuildProgress(
                    user = peer,
                    daysChecked = (startDay.toEpochDay() - checkedDay.toEpochDay()).toInt() + 1,
                )

                var shouldStop = false

                when (action) {
                    // если текущий день была коммуникация, растим
                    Action.GROW -> currentDay = currentDay.prev()

                    // если он умер (только от одной стороны)
                    Action.KILL, Action.KILL_BY_OWNER, Action.KILL_BY_PEER ->
                        // если проверяемый день текущий и может быть зафриженным
                        if (checkedDay == startDay) {
                            // маркируем, что длинна по итогу rebuildTo быть вчера
                            startDayIsFrozen = true
                            // в след итерации чекаем предыдущий день
                            currentDay = checkedDay.prev()
                        } else {
                            // устанавливаем rebuildFrom как следующий, ибо reviveNow не было
                            currentDay = checkedDay.next()
                            shouldStop = true
                        }

                    Action.REVIVE -> {
                        // Добавляем reviveNow, что бы след индексация была быстрее
                        revives.add(checkedDay)
                        // Скипаем текущий и предыдущий день, так как сегодня reviveNow, а вчера 100% сдох
                        currentDay = checkedDay.minusDays(2)
                    }
                }

                onProgressUpdate(progress)

                if (shouldStop)
                    break
            }

            val rebuildFrom = currentDay
            val rebuildTo = if (startDayIsFrozen) startDay.prev() else startDay

            if (sendServiceMessages)
                logger.info("Rebuild service messages policy is unexpectedly enabled")

            db.withTransaction {
                dao.deleteByRelation(ownerUserId, peerUserId)

                dao.insertAll(
                    Streak(
                        ownerUserId,
                        peerUserId,
                        rebuildFrom,
                        rebuildTo,
                        rebuildTo,
                        revives.size
                    )
                )

                // revives are children of streak on db side,
                // so we need to insert already existing values too
                revives.forEach {
                    val record = StreakRevive(ownerUserId, peerUserId, it)
                    reviveDao.insertAll(record)
                }
            }

            // TODO: service message abt upgrade?
        } catch (e: Throwable) {
            logger.fatal("Failed to rebuild peer $accountId:${peer.id}", e)
        } finally {
            if (!ignoreLock)
                rebuildLock.set(false)
        }
    }

    suspend fun rebuildAll(
        accountId: Int,
        sendServiceMessages: Boolean = false,
        onProgressUpdate: (index: Int, total: Int, peer: TLRPC.User, progress: RebuildProgress) -> Unit
    ): RebuildAllResult {
        if (!rebuildLock.compareAndSet(false, true)) {
            logger.info("Unable to rebuild all peers for $accountId because another rebuild is already running")
            return RebuildAllResult(0, emptyList())
        }

        try {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val revives = reviveDao.findAllByOwnerUserId(ownerUserId).toSet()
            val peers = collectEligiblePrivateUsers(accountId)

            for ((index, user) in peers.withIndex()) {
                val peerRevives =
                    revives.filter { it.peerUserId == user.id }.map { it.revivedAt }.toSet()

                rebuild(accountId, user, peerRevives, true, sendServiceMessages) {
                    onProgressUpdate(index, peers.size, user, it)
                }

            }

            return RebuildAllResult(
                totalChats = peers.size,
                uiSyncTargets = peers.map { UiSyncTarget(accountId, it.id) },
            )
        } finally {
            rebuildLock.set(false)
        }
    }

    private suspend fun checkForUpdates(
        accountId: Int,
        streak: Streak,
        sendServiceMessages: Boolean = true
    ) {
        val now = LocalDate.now()
        val previousLength = streak.length
        val previousLevelLength = streak.level.length

        if (streak.updateFromOwnerAt == streak.updateFromPeerAt && streak.updateFromPeerAt == now)
            return

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerUserId = streak.peerUserId

        val peer = MessagesController.getInstance(accountId).getUser(peerUserId) ?: return

        // if last checked day is active, check next
        var currentDay = minOf(
            streak.updateFromOwnerAt,
            streak.updateFromPeerAt,
            compareBy { it.toEpochDay() }
        ).let {
            if (streak.updateFromOwnerAt == streak.updateFromPeerAt)
                it.next()
            else
                it
        }

        var updateFromOwnerAt = streak.updateFromOwnerAt
        var updateFromPeerAt = streak.updateFromPeerAt

        val revives = reviveDao.findByRelation(
            ownerUserId,
            peerUserId
        )
            .map { it.revivedAt }
            .toMutableSet()

        while (true) {
            if (currentDay > now)
                break

            when (val action =
                fetchStreakActionForDay(accountId, peer, currentDay, revives, false)) {
                Action.GROW -> {
                    updateFromOwnerAt = currentDay
                    updateFromPeerAt = currentDay
                    currentDay = currentDay.next()
                }

                Action.REVIVE -> {
                    revives.add(currentDay)
                    updateFromOwnerAt = currentDay
                    updateFromPeerAt = currentDay
                    currentDay = currentDay.next()
                }

                Action.KILL, Action.KILL_BY_OWNER, Action.KILL_BY_PEER -> {
                    if (currentDay == now) {
                        if (action == Action.KILL_BY_OWNER)
                            updateFromPeerAt = currentDay
                        else if (action == Action.KILL_BY_PEER)
                            updateFromOwnerAt = currentDay

                        break
                    }

                    val restoredAfterDeath = fetchStreakActionForDay(
                        accountId,
                        peer,
                        currentDay.next(),
                        revives,
                        true
                    ) == Action.REVIVE

                    if (!restoredAfterDeath) {
                        val streakBeforeDeath = Streak(
                            streak.ownerUserId,
                            streak.peerUserId,
                            streak.createdAt,
                            updateFromOwnerAt,
                            updateFromPeerAt,
                            revives.size,
                        )

                        kill(
                            accountId,
                            peerUserId,
                            sendServiceMessages && isVisibleLength(streakBeforeDeath.length)
                        )
                        return
                    }

                    val reviveDay = currentDay.next()
                    revives.add(reviveDay)
                    updateFromOwnerAt = reviveDay
                    updateFromPeerAt = reviveDay
                    // скипнуть текущий день смерти и следующий с reviveNow
                    currentDay = reviveDay.next()
                }
            }
        }

        db.withTransaction {
            dao.update(
                streak.copy(
                    updateFromOwnerAt = updateFromOwnerAt,
                    updateFromPeerAt = updateFromPeerAt,
                    revivesCount = revives.size
                )
            )

            revives.forEach { reviveDao.insertAll(StreakRevive(ownerUserId, peerUserId, it)) }
        }

        val currentStreak = get(accountId, peerUserId) ?: return

        if (
            sendServiceMessages
            && isVisibleLength(previousLength)
            && currentStreak.level.length > previousLevelLength
        ) {
            serviceMessagesController.sendUpgrade(accountId, peerUserId, currentStreak.length)
        }
    }

    suspend fun checkAllForUpdates(): List<UiSyncTarget> {
        val uiSyncTargets = mutableListOf<UiSyncTarget>()

        for (accountId in userConfigAuthorizedIds) {
            val streaks =
                dao.findAllByOwnerUserId(UserConfig.getInstance(accountId).clientUserId)

            streaks.forEach {
                checkForUpdates(accountId, it)
                uiSyncTargets.add(UiSyncTarget(accountId, it.peerUserId))
            }
        }

        return uiSyncTargets
    }

    suspend fun handleUpdate(
        accountId: Int,
        peerUserId: Long,
        at: LocalDate,
        out: Boolean,
        message: String?,
        sendServiceMessages: Boolean = true
    ): HandleUpdateResult {
        val now = minOf(at, LocalDate.now(), compareBy { it.toEpochDay() })

        val peerType = getPeerType(accountId, peerUserId)

        // ignore invalid peers
        if (peerType == PeerType.INVALID)
            return HandleUpdateResult(changed = false, created = false)

        val streak = get(accountId, peerUserId) ?: run {
            // forbid streak creation for bots
            if (peerType == PeerType.BOT)
                return HandleUpdateResult(changed = false, created = false)

            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            val updateFromOwnerAt: LocalDate
            val updateFromPeerAt: LocalDate

            if (out) {
                updateFromOwnerAt = now
                updateFromPeerAt = now.prev()
            } else {
                updateFromOwnerAt = now.prev()
                updateFromPeerAt = now
            }

            val streak = Streak(
                ownerUserId,
                peerUserId,
                now,
                updateFromOwnerAt,
                updateFromPeerAt,
                0
            )

            dao.insertAll(streak)

            if (sendServiceMessages && isVisibleLength(streak.length))
                serviceMessagesController.sendCreation(accountId, peerUserId)

            return HandleUpdateResult(
                changed = true,
                created = isVisibleLength(streak.length),
            )
        }

        if (ServiceMessage.isServiceText(message)) {
            // do not handle revives for bots
            if (peerType == PeerType.VALID
                && message == ServiceMessage.RESTORE_TEXT
                && streak.canRevive
            ) {
                reviveNow(accountId, peerUserId, false)
                return HandleUpdateResult(changed = true, created = false)
            }

            return HandleUpdateResult(changed = false, created = false)
        }

        val wasCreated = !isVisibleLength(streak.length)

        if (out) {
            if (streak.updateFromOwnerAt == now)
                return HandleUpdateResult(changed = false, created = false)
            else
                dao.update(streak.copy(updateFromOwnerAt = now))
        } else {
            if (streak.updateFromPeerAt == now)
                return HandleUpdateResult(changed = false, created = false)
            else
                dao.update(streak.copy(updateFromPeerAt = now))
        }

        val updatedStreak = get(accountId, peerUserId)
            ?: return HandleUpdateResult(changed = false, created = false)

        streakPopupController.enqueueForTransition(accountId, peerUserId, streak, updatedStreak)

        if (sendServiceMessages
            && !isVisibleLength(streak.length)
            && isVisibleLength(updatedStreak.length)
        ) serviceMessagesController.sendCreation(accountId, peerUserId)
        else if (sendServiceMessages
            && isVisibleLength(streak.length)
            && updatedStreak.level.length > streak.level.length
        ) serviceMessagesController.sendUpgrade(accountId, peerUserId, updatedStreak.length)

        return HandleUpdateResult(
            changed = true,
            created = wasCreated && isVisibleLength(updatedStreak.length),
        )
    }

    fun toggleServiceMessages(accountId: Int, peerUserId: Long): Boolean =
        serviceMessagesController.toggle(accountId, peerUserId)

    fun setServiceMessagesEnabled(accountId: Int, peerUserId: Long, enabled: Boolean): Boolean =
        serviceMessagesController.setEnabled(accountId, peerUserId, enabled)

    suspend fun flushCurrentChatPopup() =
        streakPopupController.flushCurrentChat()

    suspend fun get(accountId: Int, peerUserId: Long): Streak? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun getViewData(accountId: Int, peerUserId: Long): StreakViewData? {
        val streak =
            dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)
                ?.let { if (it.dead || !isVisibleLength(it.length)) null else it }
                ?: return null

        val streakLevel = streak.level

        return StreakViewData(
            streak.length,
            streakLevel.documentId,
            streakLevel.color,
            streak.length == streakLevel.length || streak.length % 100 == 0
        )
    }

    fun getViewDataBlocking(accountId: Int, peerUserId: Long): StreakViewData? =
        runBlocking { getViewData(accountId, peerUserId) }

    suspend fun findStartMessageId(accountId: Int, peerUserId: Long): Int? {
        val streak = get(accountId, peerUserId) ?: return null
        val peer = MessagesController.getInstance(accountId).getInputPeer(peerUserId) ?: return null
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        val startTs = streak.createdAt.toEpochSecondUtc().toInt()
        val endTs = streak.createdAt.plusDays(1).toEpochSecondUtc().toInt()

        var offsetId = 0
        var offsetDate = endTs
        var firstId = 0
        var firstDate = 0

        while (true) {
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_id = offsetId
                offset_date = offsetDate
                limit = 100
            }

            val deferred = CompletableDeferred<Result<TLObject>>()

            connectionsManager.sendRequest(req) { response, error ->
                if (error != null) {
                    deferred.complete(
                        Result.failure(RuntimeException(error.text ?: error.toString()))
                    )
                } else {
                    deferred.complete(
                        Result.success(
                            response ?: throw NullPointerException("History response is null")
                        )
                    )
                }
            }

            val response = deferred.await().getOrElse { throw it } as? TLRPC.messages_Messages
                ?: throw RuntimeException("Unexpected history response type")
            val messages = response.messages

            if (messages.isEmpty())
                break

            for (messageAny in messages) {
                val message = messageAny as? TLRPC.Message ?: continue
                val messageDate = message.date

                if (messageDate !in startTs until endTs)
                    continue

                if (
                    firstId == 0
                    || messageDate < firstDate
                    || (messageDate == firstDate && message.id < firstId)
                ) {
                    firstId = message.id
                    firstDate = messageDate
                }
            }

            val oldest = messages.lastOrNull() as? TLRPC.Message ?: break
            offsetId = oldest.id
            offsetDate = oldest.date

            if (oldest.date < startTs)
                break
        }

        return firstId.takeIf { it > 0 }
    }


    suspend fun syncUserState(accountId: Int, peerUserId: Long) {
        val messagesController = MessagesController.getInstance(accountId)
        val user = messagesController.getUser(peerUserId)

        if (getViewData(accountId, peerUserId) == null) {
            restoreUser(accountId, peerUserId)
            return
        }

        if (user == null)
            return

        patchUser(accountId, user)
        messagesController.putUser(user, false, true)
    }

    suspend fun debugSetThreeDayStreak(accountId: Int, peerUserId: Long): Int {
        val now = LocalDate.now()
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val streak = buildStreak(ownerUserId, peerUserId, 3, now, now)

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insertAll(streak)
        }

        streakPopupController.enqueueCreated(accountId, peerUserId, streak.length, streak.level)
        serviceMessagesController.sendCreation(accountId, peerUserId)

        return streak.length
    }

    suspend fun debugUpgradeStreak(accountId: Int, peerUserId: Long): Int? {
        val streak = get(accountId, peerUserId) ?: return null

        val nextLevelLength = Plugin.getInstance()
            .streakLevelRegistry
            .levels()
            .firstOrNull { it.length > streak.level.length }
            ?.length
            ?: return streak.level.length

        val upgraded = buildStreak(
            streak.ownerUserId,
            streak.peerUserId,
            nextLevelLength,
            streak.updateFromOwnerAt,
            streak.updateFromPeerAt,
            streak.revivesCount,
        )

        dao.update(upgraded)
        streakPopupController.enqueueUpgrade(accountId, peerUserId, upgraded.length, upgraded.level)
        serviceMessagesController.sendUpgrade(accountId, peerUserId, upgraded.length)

        return upgraded.length
    }

    suspend fun debugFreezeStreak(accountId: Int, peerUserId: Long): Int {
        val existing = get(accountId, peerUserId)
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val yesterday = LocalDate.now().prev()
        val length = existing?.length ?: 3
        val revivesCount = existing?.revivesCount ?: 0

        val streak = buildStreak(
            ownerUserId,
            peerUserId,
            length,
            yesterday,
            yesterday,
            revivesCount,
        )

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insertAll(streak)
        }

        return streak.length
    }

    suspend fun debugMarkDead(accountId: Int, peerUserId: Long): Int {
        val existing = get(accountId, peerUserId)
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val deathDay = LocalDate.now().minusDays(2)
        val length = existing?.length ?: 3
        val revivesCount = existing?.revivesCount ?: 0

        val streak = buildStreak(
            ownerUserId,
            peerUserId,
            length,
            deathDay,
            deathDay,
            revivesCount,
        )

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insertAll(streak)
        }

        serviceMessagesController.sendDeath(accountId, peerUserId)

        return streak.length
    }

    suspend fun debugDeleteStreak(accountId: Int, peerUserId: Long): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val streak = dao.findByRelation(ownerUserId, peerUserId) ?: return false

        dao.delete(streak)

        return true
    }

    suspend fun kill(
        accountId: Int,
        peerUserId: Long,
        sendServiceMessage: Boolean = true
    ) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        dao.deleteByRelation(ownerUserId, peerUserId)

        if (sendServiceMessage) {
            serviceMessagesController.sendDeath(accountId, peerUserId)
        }
    }

    suspend fun reviveNow(
        accountId: Int,
        peerUserId: Long,
        sendServiceMessage: Boolean = true
    ): Boolean {
        val streak = get(accountId, peerUserId) ?: return false

        if (!streak.canRevive)
            return false

        val now = LocalDate.now()

        val alreadyRevived = reviveDao.isRevived(streak.ownerUserId, streak.peerUserId, now)

        db.withTransaction {
            dao.update(
                streak.copy(
                    revivesCount = if (alreadyRevived) streak.revivesCount else streak.revivesCount + 1,
                    updateFromOwnerAt = now,
                    updateFromPeerAt = now
                )
            )

            reviveDao.insertAll(
                StreakRevive(
                    streak.ownerUserId,
                    streak.peerUserId,
                    now
                )
            )
        }

        val revivedStreak = get(accountId, peerUserId)

        if (sendServiceMessage && revivedStreak != null && isVisibleLength(revivedStreak.length)) {
            serviceMessagesController.sendRestore(accountId, peerUserId)
        }

        return true
    }

    suspend fun patchUser(accountId: Int, user: TLRPC.User) {
        val streakViewData = getViewData(accountId, user.id) ?: return
        val currentEmojiStatusDocumentId = UserObject.getEmojiStatusDocumentId(user.emoji_status)
        val isCurrentEmojiStatusPatched = currentEmojiStatusDocumentId != null &&
                Plugin.getInstance()
                    .streakLevelRegistry
                    .levels()
                    .any { it.documentId == currentEmojiStatusDocumentId }

        if (!isCurrentEmojiStatusPatched) {
            originalUserStates.putIfAbsent(
                user.id,
                OriginalUserState(
                    premium = user.premium,
                    emojiStatus = user.emoji_status
                )
            )
        }

        user.premium = true

        @Suppress("CAST_NEVER_SUCCEEDS")
        user.emoji_status = TLRPC.TL_emojiStatus()
            .apply { document_id = streakViewData.documentId } as TLRPC.EmojiStatus
    }

    suspend fun patchUsers(accountId: Int) {
        val messagesController = MessagesController.getInstance(accountId)
        val streaks = dao.findAllByOwnerUserId(UserConfig.getInstance(accountId).clientUserId)

        for (streak in streaks) {
            val user = messagesController.getUser(streak.peerUserId) ?: continue

            patchUser(accountId, user)

            messagesController.putUser(user, false, true)
        }
    }

    private fun restoreUser(accountId: Int, userId: Long) {
        val messagesController = MessagesController.getInstance(accountId)
        val originalState = originalUserStates.remove(userId) ?: return
        val user = messagesController.getUser(userId) ?: return

        user.premium = originalState.premium
        user.emoji_status = originalState.emojiStatus

        messagesController.putUser(user, false, true)
    }

    fun restorePatchedUsers() {
        val states = originalUserStates.toMap()

        for (accountId in userConfigAuthorizedIds) {
            val messagesController = MessagesController.getInstance(accountId)

            for ((userId, originalState) in states) {
                val user = messagesController.getUser(userId) ?: continue

                user.premium = originalState.premium
                user.emoji_status = originalState.emojiStatus

                messagesController.putUser(user, false, true)
                originalUserStates.remove(userId)
            }
        }
    }

    suspend fun pruneInvalid() {
        db.withTransaction {
            for (accountId in userConfigAuthorizedIds) {
                val ownerUserId = UserConfig.getInstance(accountId).clientUserId

                dao.findAllByOwnerUserId(ownerUserId)
                    .filterNot { isPeerValidOrBot(accountId, it.peerUserId) }
                    .forEach { dao.delete(it) }
            }
        }
    }
}
