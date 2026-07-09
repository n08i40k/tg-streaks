package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.chat_history_fetcher.CachedChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.RemoteChatHistoryFetcher
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakActivityCache
import ru.n08i40k.streaks.data.StreakActivityStatus
import ru.n08i40k.streaks.data.StreakManualRevive
import ru.n08i40k.streaks.data.StreakRevive
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.exception.InvalidPeerException
import ru.n08i40k.streaks.extension.PeerType
import ru.n08i40k.streaks.extension.fmt
import ru.n08i40k.streaks.extension.getPeerType
import ru.n08i40k.streaks.extension.isPeerIdInvalid
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.isPeerValidOrBot
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.minusDays
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.now
import ru.n08i40k.streaks.extension.plusDays
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.toEpochSecondSystem
import ru.n08i40k.streaks.extension.toEpochSecondUtc
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RateLimitContext
import ru.n08i40k.streaks.util.RebuildNotificationHelper
import ru.n08i40k.streaks.util.RuntimeGuard
import ru.n08i40k.streaks.util.fetchPeerUsers
import kotlinx.datetime.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

@OptIn(DelicateCoroutinesApi::class)
class StreaksController(
    private val db: PluginDatabase,
    resourcesProvider: ResourcesProvider,
) {
    companion object {
        private const val MAX_MANUAL_CALENDAR_REVIVES_PER_CHAT = 2
    }

    data class UiSyncTarget(
        val accountId: Int,
        val peerUserId: Long,
    )

    data class RebuildAllResult(
        val totalChats: Int,
        val uiSyncTargets: List<UiSyncTarget>,
    )

    data class RebuildProgress(
        val peerUser: TLRPC.User,
        val daysChecked: Int,
    )

    data class CalendarInteractionSnapshot(
        val streak: Streak?,
        val cachedActivity: List<StreakActivityCache>,
        val revivedDays: Set<LocalDate>,
        val manualRevivesUsed: Int,
    )

    sealed class CalendarTapDecision {
        object Ignore : CalendarTapDecision()
        object LimitReached : CalendarTapDecision()
        object WarnTapNextDay : CalendarTapDecision()

        data class OfferManualRevive(
            val reviveDay: LocalDate,
            val reason: Reason,
        ) : CalendarTapDecision()

        enum class Reason {
            FIRST_LIVE_DAY_AFTER_UNRESTORED_GAP,
            DEAD_CHAIN_RESTORE,
        }
    }

    enum class AddManualCalendarReviveResult {
        Added,
        AlreadyExists,
        LimitReached,
    }

    private val rebuildLock = AtomicBoolean(false)

    private val cachedFetcher: ChatHistoryFetcher = CachedChatHistoryFetcher()
    private val remoteFetcher: ChatHistoryFetcher = RemoteChatHistoryFetcher()
    private val streakPopupController = StreakPopupController(db, resourcesProvider)

    private val activityCacheDao = db.streakActivityCacheDao()
    private val dao = db.streakDao()
    private val manualReviveDao = db.streakManualReviveDao()
    private val reviveDao = db.streakReviveDao()

    private suspend fun loadEffectiveRevivedDays(
        ownerUserId: Long,
        peerUserId: Long,
        cachedActivity: List<StreakActivityCache>,
    ): Set<LocalDate> =
        buildSet {
            reviveDao.findByRelation(ownerUserId, peerUserId)
                .mapTo(this) { it.revivedAt }
            manualReviveDao.findByRelation(ownerUserId, peerUserId)
                .mapTo(this) { it.revivedAt }
            cachedActivity.filter { it.wasRevived }
                .mapTo(this) { it.day }
        }

    private suspend fun loadReviveDates(
        ownerUserId: Long,
        peerUserId: Long,
    ): MutableSet<LocalDate> =
        buildSet {
            reviveDao.findByRelation(ownerUserId, peerUserId)
                .mapTo(this) { it.revivedAt }
            manualReviveDao.findByRelation(ownerUserId, peerUserId)
                .mapTo(this) { it.revivedAt }
        }.toMutableSet()

    private fun isBoth(
        activityByDay: Map<LocalDate, StreakActivityStatus>,
        day: LocalDate,
    ): Boolean = activityByDay[day] == StreakActivityStatus.BOTH

    private fun isAliveLike(
        activityByDay: Map<LocalDate, StreakActivityStatus>,
        revivedDays: Set<LocalDate>,
        day: LocalDate,
    ): Boolean = isBoth(activityByDay, day) || revivedDays.contains(day)

    private fun isDead(
        activityByDay: Map<LocalDate, StreakActivityStatus>,
        revivedDays: Set<LocalDate>,
        day: LocalDate,
    ): Boolean = !isBoth(activityByDay, day) && !revivedDays.contains(day.next())

    private suspend fun removeInvalidPeerStreak(accountId: Int, peerUserId: Long) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        db.withTransaction {
            val streak = dao.findByRelation(ownerUserId, peerUserId)

            dao.deleteByRelation(ownerUserId, peerUserId)
            activityCacheDao.deleteByRelation(accountId, peerUserId)
            manualReviveDao.deleteByRelation(ownerUserId, peerUserId)

            streak?.let {
                EventBus.emit(
                    PluginEvent.StreakDeletedEvent(
                        accountId,
                        Clock.System.now(),
                        it
                    )
                )
            }
        }

        Logger.info("Removed streak for invalid peer $accountId:$peerUserId")
    }

    fun isRebuildRunning(): Boolean =
        rebuildLock.get()

    private fun buildStreak(
        ownerUserId: Long,
        peerUserId: Long,
        length: Int,
        updateFromOwnerAt: LocalDate,
        updateFromPeerAt: LocalDate,
        revivesCount: Int = 0,
        deathNotified: Boolean = false,
    ): Streak {
        val minUpdateAt = minOf(updateFromOwnerAt, updateFromPeerAt)

        val createdAt =
            minUpdateAt.minusDays((length + revivesCount - 1).toLong().coerceAtLeast(0L))

        return Streak(
            ownerUserId,
            peerUserId,
            createdAt,
            updateFromOwnerAt,
            updateFromPeerAt,
            revivesCount,
            deathNotified,
        )
    }

    private fun normalizeStatus(status: ChatHistoryFetcher.Status): StreakActivityStatus =
        StreakActivityStatus.fromFetcherStatus(status)

    private suspend fun upsertActivityCache(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        status: ChatHistoryFetcher.Status,
    ) {
        upsertActivityCache(
            accountId,
            peerUserId,
            day,
            normalizeStatus(status),
            status.wasRevived
        )
    }

    private suspend fun upsertActivityCache(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
        status: StreakActivityStatus,
        wasRevived: Boolean,
    ) {
        activityCacheDao.insertOrReplace(
            StreakActivityCache(
                accountId = accountId,
                peerUserId = peerUserId,
                day = day,
                status = status.code,
                wasRevived = wasRevived,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun mergeActivityCacheFromUpdate(
        accountId: Int,
        peerUserId: Long,
        at: LocalDate,
        out: Boolean,
        message: String?,
    ) {
        val existing = activityCacheDao.findByRelationAndDay(accountId, peerUserId, at)

        if (message == ServiceMessage.RESTORE_TEXT) {
            upsertActivityCache(
                accountId,
                peerUserId,
                at,
                existing?.let { StreakActivityStatus.fromCode(it.status) }
                    ?: StreakActivityStatus.NO_ACTIVITY,
                true
            )
            return
        }

        if (ServiceMessage.isServiceText(message))
            return

        val mergedStatus =
            existing?.let { StreakActivityStatus.fromCode(it.status) }
                ?.mergeMessage(out)
                ?: StreakActivityStatus.NO_ACTIVITY.mergeMessage(out)

        upsertActivityCache(
            accountId,
            peerUserId,
            at,
            mergedStatus,
            existing?.wasRevived ?: false
        )
    }

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
            dialogId = dialog.peer?.let { peerUser ->
                try {
                    DialogObject.getPeerDialogId(peerUser)
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
        peerUser: TLRPC.User,
        day: LocalDate,
        revives: Set<LocalDate>,
        untilRevive: Boolean
    ): Action {
        if (revives.contains(day)) {
            val existingStatus =
                activityCacheDao.findByRelationAndDay(accountId, peerUser.id, day)
                    ?.let { StreakActivityStatus.fromCode(it.status) }
                    ?: StreakActivityStatus.NO_ACTIVITY

            upsertActivityCache(
                accountId,
                peerUser.id,
                day,
                existingStatus,
                true
            )
            return Action.REVIVE
        }

        when (val status =
            cachedFetcher.fetchActivity(accountId, peerUser.id, day, untilRevive)) {
            is ChatHistoryFetcher.Status.FromBoth -> {
                upsertActivityCache(accountId, peerUser.id, day, status)

                if (status.wasRevived)
                    return Action.REVIVE

                if (!untilRevive)
                    return Action.GROW
            }

            is ChatHistoryFetcher.Status.FromOwner -> {
                upsertActivityCache(accountId, peerUser.id, day, status)

                if (status.wasRevived)
                    return Action.REVIVE
            }

            is ChatHistoryFetcher.Status.FromPeer -> {
                upsertActivityCache(accountId, peerUser.id, day, status)

                if (status.wasRevived)
                    return Action.REVIVE
            }

            is ChatHistoryFetcher.Status.NoActivity -> {
                upsertActivityCache(accountId, peerUser.id, day, status)

                if (status.wasRevived)
                    return Action.REVIVE
            }
        }

        return when (val status =
            remoteFetcher.fetchActivity(accountId, peerUser.id, day, untilRevive)) {
            is ChatHistoryFetcher.Status.FromBoth -> {
                upsertActivityCache(accountId, peerUser.id, day, status)
                if (status.wasRevived) Action.REVIVE else Action.GROW
            }

            is ChatHistoryFetcher.Status.FromOwner -> {
                upsertActivityCache(accountId, peerUser.id, day, status)
                if (status.wasRevived) Action.REVIVE else Action.KILL_BY_PEER
            }

            is ChatHistoryFetcher.Status.FromPeer -> {
                upsertActivityCache(accountId, peerUser.id, day, status)
                if (status.wasRevived) Action.REVIVE else Action.KILL_BY_OWNER
            }

            is ChatHistoryFetcher.Status.NoActivity -> {
                upsertActivityCache(accountId, peerUser.id, day, status)
                if (status.wasRevived) Action.REVIVE else Action.KILL
            }
        }
    }

    // lock and user-facing feedback (notifications) are handled by rebuild/rebuildAll;
    // callers must hold rebuildLock before calling this
    private suspend fun rebuildOne(
        accountId: Int,
        peerUser: TLRPC.User,
        onProgressUpdate: suspend (progress: RebuildProgress) -> Unit,
    ) {
        try {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val peerUserId = peerUser.id

            val revives = loadReviveDates(ownerUserId, peerUserId)

            val startDay = LocalDate.now()
            var currentDay = LocalDate.now()

            var startDayIsFrozen = false

            while (true) {
                val checkedDay = currentDay
                val action =
                    fetchStreakActionForDay(
                        accountId,
                        peerUser,
                        checkedDay,
                        revives,
                        false
                    )
                Logger.info("[StreakRebuild] $action at ${currentDay.fmt()} for $accountId:$peerUserId")

                val progress = RebuildProgress(
                    peerUser = peerUser,
                    daysChecked = (startDay.toEpochDays() - checkedDay.toEpochDays()).toInt() + 1,
                )

                var shouldStop = false

                when (action) {
                    // если текущий день была коммуникация, растим
                    Action.GROW -> currentDay = currentDay.prev()

                    // если он умер (только от одной стороны)
                    Action.KILL, Action.KILL_BY_OWNER, Action.KILL_BY_PEER ->
                        // если проверяемый день текущий и может быть зафриженным
                        if (checkedDay == startDay) {
                            // revive может быть и сегодня
                            if (fetchStreakActionForDay(
                                    accountId,
                                    peerUser,
                                    checkedDay,
                                    revives,
                                    true
                                ) == Action.REVIVE
                            ) {
                                Logger.info("[StreakRebuild] First-day-revive at ${currentDay.fmt()} for $accountId:$peerUserId")
                                revives.add(checkedDay)
                                currentDay = checkedDay.minusDays(2)
                                continue
                            }

                            // маркируем, что длинна по итогу rebuildTo быть вчера
                            startDayIsFrozen = true
                            // в след итерации чекаем предыдущий день
                            currentDay = checkedDay.prev()
                        } else {
                            val action = fetchStreakActionForDay(
                                accountId,
                                peerUser,
                                checkedDay.next(),
                                revives,
                                true
                            )

                            Logger.info(
                                "[StreakRebuild] Checking if next day was revive. $action at ${
                                    currentDay.next().fmt()
                                } for $accountId:$peerUserId"
                            )

                            if (action == Action.REVIVE) {
                                revives.add(checkedDay.next())
                                currentDay = checkedDay.prev()
                                continue
                            }

                            // устанавливаем rebuildFrom как следующий, ибо revive не было
                            currentDay = checkedDay.next()
                            shouldStop = true
                        }

                    Action.REVIVE -> {
                        // Добавляем revive, что бы след индексация была быстрее
                        revives.add(checkedDay)
                        // Скипаем текущий и предыдущий день, так как сегодня revive, а вчера 100% сдох
                        currentDay = checkedDay.minusDays(2)
                    }
                }

                onProgressUpdate(progress)

                if (shouldStop)
                    break
            }

            val rebuildFrom = currentDay
            val rebuildTo = if (startDayIsFrozen) startDay.prev() else startDay

            if (rebuildFrom > rebuildTo) {
                var sourceStreak: Streak? = null

                db.withTransaction {
                    sourceStreak = dao.findByRelation(ownerUserId, peerUserId)
                    dao.deleteByRelation(ownerUserId, peerUserId)
                }

                sourceStreak?.let {
                    if (it.isVisible) {
                        EventBus.emit(
                            PluginEvent.StreakDeletedEvent(accountId, Clock.System.now(), it)
                        )
                    }
                }

                return
            }

            var sourceStreak: Streak? =
                null

            val targetStreak =
                Streak(
                    ownerUserId,
                    peerUserId,
                    rebuildFrom,
                    rebuildTo,
                    rebuildTo,
                    revives.size
                )

            db.withTransaction {
                sourceStreak = dao.findByRelation(ownerUserId, peerUserId)

                dao.insertOrReplace(targetStreak)
                reviveDao.insertAll(revives.map { StreakRevive(ownerUserId, peerUserId, it) })
            }

            EventBus.emit(
                PluginEvent.StreakRebuiltEvent(
                    accountId,
                    Clock.System.now(),
                    sourceStreak,
                    targetStreak
                )
            )
        } catch (_: InvalidPeerException) {
            removeInvalidPeerStreak(accountId, peerUser.id)
        } catch (e: Throwable) {
            Logger.fatal("Failed to rebuild peer $accountId:${peerUser.id}", e)
        }
    }

    // TODO: this still drives RebuildNotificationHelper directly; will be replaced once
    // RebuildNotificationHelper itself is reworked in a follow-up commit
    private suspend fun withRateLimitNotification(
        peerName: String,
        block: suspend () -> Unit,
    ) = withContext(RateLimitContext { throttlingClock ->
        if (throttlingClock == null) {
            RebuildNotificationHelper.cancelRateLimitNotification()
            return@RateLimitContext
        }

        val (elapsedSec, totalSec) = throttlingClock

        RebuildNotificationHelper.showRateLimitCountdown(
            peerName,
            remainingMs = (totalSec - elapsedSec) * 1000L,
            totalMs = totalSec * 1000L,
        )
    }) {
        block()
    }

    suspend fun rebuild(
        accountId: Int,
        peerUser: TLRPC.User,
        ignoreLock: Boolean = false,
        onProgressUpdate: (progress: RebuildProgress) -> Unit,
    ) {
        if (!ignoreLock && !rebuildLock.compareAndSet(false, true)) {
            Logger.info("Unable to rebuild peer $accountId:${peerUser.id} because another rebuild is already running")
            return
        }

        try {
            withRateLimitNotification(peerUser.label) {
                rebuildOne(accountId, peerUser) { onProgressUpdate(it) }
            }
        } finally {
            if (!ignoreLock)
                rebuildLock.set(false)
        }
    }

    suspend fun rebuildAll(
        accountId: Int,
        onProgressUpdate: (index: Int, total: Int, peerUser: TLRPC.User, progress: RebuildProgress) -> Unit,
    ): RebuildAllResult {
        if (!rebuildLock.compareAndSet(false, true)) {
            Logger.info("Unable to rebuild all peers for $accountId because another rebuild is already running")
            return RebuildAllResult(0, emptyList())
        }

        try {
            val peers = collectEligiblePrivateUsers(accountId)

            for ((index, peerUser) in peers.withIndex()) {
                rebuild(accountId, peerUser, true) {
                    onProgressUpdate(index, peers.size, peerUser, it)
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
        onProgressUpdate: ((daysChecked: Int, totalDays: Int) -> Unit)? = null,
    ) {
        val now = LocalDate.now()

        if (streak.updateFromOwnerAt == streak.updateFromPeerAt && streak.updateFromPeerAt == now)
            return

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerUserId = streak.peerUserId

        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId) ?: return

        // if last checked day is active, check next
        var currentDay = minOf(
            streak.updateFromOwnerAt,
            streak.updateFromPeerAt,
        ).let {
            if (streak.updateFromOwnerAt == streak.updateFromPeerAt)
                it.next()
            else
                it
        }

        val startDay = currentDay
        val totalDays = (now.toEpochDays() - startDay.toEpochDays() + 1L).coerceAtLeast(0L).toInt()

        // TODO: rename
        var dynStreak = streak

        val revives = reviveDao.findByRelation(ownerUserId, peerUserId)
            .map { it.revivedAt }
            .toMutableSet()

        while (true) {
            if (currentDay > now)
                break

            when (val action =
                fetchStreakActionForDay(accountId, peerUser, currentDay, revives, false)) {
                Action.GROW -> {
                    dynStreak = dynStreak.copy(
                        deathNotified = false,
                        warningNotified = false,
                        updateFromOwnerAt = currentDay,
                        updateFromPeerAt = currentDay
                    )

                    currentDay = currentDay.next()
                }

                Action.REVIVE -> {
                    revives.add(currentDay)

                    dynStreak = dynStreak.copy(
                        deathNotified = false,
                        warningNotified = false,
                        updateFromOwnerAt = currentDay,
                        updateFromPeerAt = currentDay,
                        revivesCount = revives.size
                    )

                    currentDay = currentDay.next()
                }

                Action.KILL, Action.KILL_BY_OWNER, Action.KILL_BY_PEER -> {
                    if (currentDay == now) {
                        if (action == Action.KILL_BY_OWNER)
                            dynStreak = dynStreak.copy(updateFromPeerAt = currentDay)
                        else if (action == Action.KILL_BY_PEER)
                            dynStreak = dynStreak.copy(updateFromOwnerAt = currentDay)

                        onProgressUpdate?.invoke(totalDays, totalDays)
                        break
                    }

                    if (fetchStreakActionForDay(
                            accountId,
                            peerUser,
                            currentDay.next(),
                            revives,
                            true
                        ) == Action.REVIVE
                    ) {
                        val reviveDay = currentDay.next()
                        revives.add(reviveDay)

                        dynStreak = dynStreak.copy(
                            deathNotified = false,
                            warningNotified = false,
                            updateFromOwnerAt = reviveDay,
                            updateFromPeerAt = reviveDay,
                            revivesCount = revives.size
                        )

                        // скипнуть текущий день смерти и следующий с revive
                        currentDay = reviveDay.next()
                        continue
                    }

                    if (dynStreak.canRevive) {
                        val resStreak = dynStreak.copy(
                            deathNotified = true,
                            warningNotified = false
                        )

                        db.withTransaction {
                            dao.update(resStreak)
                            reviveDao.insertBatch(ownerUserId, peerUserId, revives)
                        }

                        if (dynStreak.isVisible && !dynStreak.deathNotified) {
                            EventBus.emit(
                                PluginEvent.StreakLostEvent(
                                    accountId,
                                    Clock.System.now(), // TODO: extract from?
                                    resStreak
                                )
                            )
                        }
                    } else {
                        dao.deleteByRelation(ownerUserId, peerUserId)

                        if (dynStreak.isVisible && !dynStreak.deathNotified) {
                            EventBus.emit(
                                PluginEvent.StreakDeletedEvent(
                                    accountId,
                                    Clock.System.now(), // TODO: extract from?
                                    dynStreak
                                )
                            )
                        }
                    }

                    return
                }
            }

            onProgressUpdate?.invoke(
                (currentDay.toEpochDays() - startDay.toEpochDays()).toInt(),
                totalDays,
            )
        }

        val streakGrew = dynStreak.updateFromOwnerAt != streak.updateFromOwnerAt ||
                dynStreak.updateFromPeerAt != streak.updateFromPeerAt ||
                dynStreak.revivesCount != streak.revivesCount

        val lastActiveDay = minOf(dynStreak.updateFromOwnerAt, dynStreak.updateFromPeerAt)
        val deathEpochSeconds = lastActiveDay.plusDays(2).toEpochSecondSystem()
        val timeUntilDeathSeconds = deathEpochSeconds - System.currentTimeMillis() / 1000L
        val isInWarningWindow = timeUntilDeathSeconds in 1..(8 * 3600)

        dynStreak = dynStreak.copy(warningNotified = isInWarningWindow)

        db.withTransaction {
            dao.update(dynStreak)
            reviveDao.insertBatch(ownerUserId, peerUserId, revives)
        }

        if (streakGrew) {
            EventBus.emit(
                PluginEvent.StreakGrowUpEvent(
                    accountId,
                    Clock.System.now(),
                    streak,
                    dynStreak
                )
            )
        }

        if (dynStreak.isVisible && isInWarningWindow != streak.warningNotified) {
            EventBus.emit(
                PluginEvent.StreakDeathWarningEvent(
                    accountId,
                    peerUserId,
                    Clock.System.now(),
                    dynStreak,
                    peerUser.label,
                    isInWarningWindow,
                    timeUntilDeathSeconds,
                )
            )
        }
    }

    suspend fun checkAllForUpdates(
        accountId: Int,
        onProgressUpdate: ((index: Int, total: Int, peerName: String, daysChecked: Int, totalDays: Int) -> Unit)? = null,
    ): List<UiSyncTarget> {
        val uiSyncTargets = mutableListOf<UiSyncTarget>()
        val streaks = dao.findAllByOwnerUserId(UserConfig.getInstance(accountId).clientUserId)

        streaks.forEachIndexed { index, streak ->
            val peerName = MessagesController.getInstance(accountId)
                .getUser(streak.peerUserId)?.label
                ?: streak.peerUserId.toString()

            try {
                checkForUpdates(accountId, streak) { daysChecked, totalDays ->
                    onProgressUpdate?.invoke(index, streaks.size, peerName, daysChecked, totalDays)
                }
            } catch (_: InvalidPeerException) {
                removeInvalidPeerStreak(accountId, streak.peerUserId)
            }

            uiSyncTargets.add(UiSyncTarget(accountId, streak.peerUserId))
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
    ) {
        val now = minOf(at, LocalDate.now())

        val peerType = getPeerType(accountId, peerUserId)

        // ignore invalid peers
        // продолжение стриков с ботами возможно (остались ли они вообще у кого-нибудь?)
        if (peerType == PeerType.INVALID)
            return

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        mergeActivityCacheFromUpdate(accountId, peerUserId, now, out, message)

        val streak = get(accountId, peerUserId) ?: run {
            // forbid streak creation for bots
            if (peerType == PeerType.BOT)
                return

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

            dao.insert(streak)

            EventBus.emit(
                PluginEvent.StreakCreatedEvent(
                    accountId,
                    Clock.System.now(), // TODO: get from update
                    streak
                )
            )

            return
        }

        if (ServiceMessage.isServiceText(message)) {
            // do not handle revives for bots
            if (peerType == PeerType.VALID
                && message == ServiceMessage.RESTORE_TEXT
                && streak.canRevive
            ) revive(
                accountId,
                peerUserId,
                // TODO: extract at from update
                byPeer = !out
            )

            return
        }

        if (streak.dead) {
            if (streak.canRevive)
                return

            dao.deleteByRelation(ownerUserId, peerUserId)

            EventBus.emit(
                PluginEvent.StreakDeletedEvent(
                    accountId,
                    Clock.System.now(), // TODO: extract from update
                    streak
                )
            )

            return handleUpdate(accountId, peerUserId, at, out, message, sendServiceMessages)
        }

        if (out) {
            if (streak.updateFromOwnerAt != now)
                dao.update(streak.copy(updateFromOwnerAt = now, deathNotified = false))
        } else {
            if (streak.updateFromPeerAt != now)
                dao.update(streak.copy(updateFromPeerAt = now, deathNotified = false))
        }

        dao.findByRelation(ownerUserId, peerUserId)!!
            .takeIf { it.length > streak.length }
            ?.let {
                EventBus.emit(
                    PluginEvent.StreakGrowUpEvent(
                        accountId,
                        Clock.System.now(), // TODO: extract from update
                        streak,
                        it
                    )
                )

            }
    }

    suspend fun flushCurrentChatPopup() =
        streakPopupController.flushCurrentChat()

    suspend fun enqueuePopupForTransition(
        accountId: Int,
        peerUserId: Long,
        before: Streak?,
        after: Streak,
    ) = streakPopupController.enqueueForTransition(accountId, peerUserId, before, after)

    suspend fun getCalendarInteractionSnapshot(
        accountId: Int,
        peerUserId: Long,
    ): CalendarInteractionSnapshot {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val cachedActivity = activityCacheDao.findByRelation(accountId, peerUserId)
        val manualRevives = manualReviveDao.findByRelation(ownerUserId, peerUserId)

        return CalendarInteractionSnapshot(
            streak = dao.findByRelation(ownerUserId, peerUserId),
            cachedActivity = cachedActivity,
            revivedDays = loadEffectiveRevivedDays(ownerUserId, peerUserId, cachedActivity),
            manualRevivesUsed = manualRevives.size,
        )
    }

    suspend fun analyzeCalendarTap(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
    ): CalendarTapDecision {
        val snapshot = getCalendarInteractionSnapshot(accountId, peerUserId)
        if (snapshot.streak?.createdAt?.let { day < it } == true) {
            return CalendarTapDecision.Ignore
        }

        val activityByDay =
            snapshot.cachedActivity.associate { it.day to StreakActivityStatus.fromCode(it.status) }
        val revivedDays = snapshot.revivedDays

        val currentDayRevived = revivedDays.contains(day)
        if (currentDayRevived) {
            return CalendarTapDecision.Ignore
        }

        val previousDay = day.prev()
        val nextDay = day.next()
        val currentDayBoth = isBoth(activityByDay, day)
        val previousDayBoth = isBoth(activityByDay, previousDay)

        if (currentDayBoth && previousDayBoth) {
            return CalendarTapDecision.Ignore
        }

        val previousDayAliveLike = isAliveLike(activityByDay, revivedDays, previousDay)
        val nextDayRevived = revivedDays.contains(nextDay)
        val currentDayDead = isDead(activityByDay, revivedDays, day)
        val previousDayDead = isDead(activityByDay, revivedDays, previousDay)

        fun actionableDecision(
            reason: CalendarTapDecision.Reason,
        ): CalendarTapDecision =
            if (snapshot.manualRevivesUsed >= MAX_MANUAL_CALENDAR_REVIVES_PER_CHAT) {
                CalendarTapDecision.LimitReached
            } else {
                CalendarTapDecision.OfferManualRevive(day, reason)
            }

        if (currentDayBoth && !previousDayAliveLike) {
            return actionableDecision(
                CalendarTapDecision.Reason.FIRST_LIVE_DAY_AFTER_UNRESTORED_GAP
            )
        }

        if (currentDayDead && !nextDayRevived && previousDayAliveLike) {
            return CalendarTapDecision.WarnTapNextDay
        }

        if (currentDayDead && !nextDayRevived && previousDayDead) {
            return actionableDecision(CalendarTapDecision.Reason.DEAD_CHAIN_RESTORE)
        }

        return CalendarTapDecision.Ignore
    }

    suspend fun addManualCalendarRevive(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
    ): AddManualCalendarReviveResult {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        var result = AddManualCalendarReviveResult.AlreadyExists

        db.withTransaction {
            if (manualReviveDao.exists(ownerUserId, peerUserId, day)) {
                result = AddManualCalendarReviveResult.AlreadyExists
                return@withTransaction
            }

            if (manualReviveDao.countByRelation(ownerUserId, peerUserId) >=
                MAX_MANUAL_CALENDAR_REVIVES_PER_CHAT
            ) {
                result = AddManualCalendarReviveResult.LimitReached
                return@withTransaction
            }

            manualReviveDao.insertIgnore(
                StreakManualRevive(
                    ownerUserId = ownerUserId,
                    peerUserId = peerUserId,
                    revivedAt = day,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            )
            result = AddManualCalendarReviveResult.Added
        }

        return result
    }

    suspend fun get(accountId: Int, peerUserId: Long): Streak? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun getAllVisible(): List<Streak> = dao.getAll()
        .filterNot { it.dead || !it.isVisible }

    suspend fun getViewData(accountId: Int, peerUserId: Long): StreakViewData? {
        val streak =
            dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)
                ?.let { if (it.dead || !it.isVisible) null else it }
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
        val peerUser =
            MessagesController.getInstance(accountId).getInputPeer(peerUserId) ?: return null
        val connectionsManager = ConnectionsManager.getInstance(accountId)

        val startTs = streak.createdAt.toEpochSecondUtc().toInt()
        val endTs = streak.createdAt.plusDays(1).toEpochSecondUtc().toInt()

        var offsetId = 0
        var offsetDate = endTs
        var firstId = 0
        var firstDate = 0

        try {
            while (true) {
                RuntimeGuard.awaitAppForegroundAndConnection(
                    accountId,
                    "streak start lookup for $accountId:$peerUserId",
                )

                val req = TLRPC.TL_messages_getHistory().apply {
                    this.peer = peerUser
                    offset_id = offsetId
                    offset_date = offsetDate
                    limit = 100
                }

                val deferred = CompletableDeferred<Result<TLObject>>()

                connectionsManager.sendRequest(req) { response, error ->
                    if (error != null) {
                        val failure =
                            if (error.isPeerIdInvalid()) {
                                InvalidPeerException(
                                    accountId,
                                    peerUserId,
                                    "Invalid peer for streak start lookup $accountId:$peerUserId",
                                    Exception(error.fmt())
                                )
                            } else {
                                RuntimeException(error.text ?: error.toString())
                            }

                        deferred.complete(Result.failure(failure))
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

                for (message in messages) {
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

                val oldest = messages.lastOrNull() ?: break
                offsetId = oldest.id
                offsetDate = oldest.date

                if (oldest.date < startTs)
                    break
            }
        } catch (_: InvalidPeerException) {
            removeInvalidPeerStreak(accountId, peerUserId)
            return null
        }

        return firstId.takeIf { it > 0 }
    }

    suspend fun debugSetThreeDayStreak(accountId: Int, peerUserId: Long): Int {
        val now = LocalDate.now()
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val streak = buildStreak(ownerUserId, peerUserId, 3, now, now)

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insert(streak)
        }

        EventBus.emit(
            PluginEvent.StreakCreatedEvent(
                accountId,
                Clock.System.now(),
                streak
            )
        )

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

        EventBus.emit(
            PluginEvent.StreakGrowUpEvent(
                accountId,
                Clock.System.now(),
                streak,
                upgraded
            )
        )

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
            dao.insert(streak)
        }

        EventBus.emit(
            PluginEvent.StreakCreatedEvent(
                accountId,
                Clock.System.now(),
                streak
            )
        )

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
            true,
        )

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insert(streak)
        }

        EventBus.emit(
            PluginEvent.StreakLostEvent(
                accountId,
                Clock.System.now(),
                streak
            )
        )

        return streak.length
    }

    suspend fun debugDeleteStreak(accountId: Int, peerUserId: Long): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val streak = dao.findByRelation(ownerUserId, peerUserId)

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            activityCacheDao.deleteByRelation(accountId, peerUserId)
            manualReviveDao.deleteByRelation(ownerUserId, peerUserId)
        }

        if (streak != null) {
            EventBus.emit(
                PluginEvent.StreakDeletedEvent(
                    accountId,
                    Clock.System.now(),
                    streak
                )
            )
        }

        return true
    }

    suspend fun revive(
        accountId: Int,
        peerUserId: Long,
        @Suppress("unused") at: LocalDate = LocalDate.now(),
        byPeer: Boolean = false
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
                    updateFromPeerAt = now,
                    deathNotified = false,
                    warningNotified = false,
                )
            )

            reviveDao.insert(StreakRevive(streak.ownerUserId, streak.peerUserId, now))
        }

        val revivedStreak = get(accountId, peerUserId)!!

        EventBus.emit(
            PluginEvent.StreakRestoredEvent(
                accountId,
                Clock.System.now(), // TODO: extract from at
                revivedStreak,
                byPeer
            )
        )

        if (revivedStreak.length > streak.length) {
            EventBus.emit(
                PluginEvent.StreakGrowUpEvent(
                    accountId,
                    Clock.System.now(), // TODO: extract from at
                    streak,
                    revivedStreak
                )
            )
        }

        upsertActivityCache(
            accountId,
            peerUserId,
            now,
            StreakActivityStatus.BOTH,
            true
        )

        return true
    }

    suspend fun pruneInvalid(accountId: Int) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val streaks = dao.findAllByOwnerUserId(ownerUserId)

        val peerUsers = fetchPeerUsers(
            accountId,
            ArrayList(streaks.map { it.peerUserId })
        ) ?: return

        streaks
            .filterNot { isPeerValidOrBot(peerUsers[it.peerUserId]) }
            .forEach { removeInvalidPeerStreak(accountId, it.peerUserId) }
    }
}
