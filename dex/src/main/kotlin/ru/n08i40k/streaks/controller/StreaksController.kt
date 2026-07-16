package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.chat_history_fetcher.CachedChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.RemoteChatHistoryFetcher
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakRestore
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.exception.InvalidPeerException
import ru.n08i40k.streaks.extension.PeerType
import ru.n08i40k.streaks.extension.fmt
import ru.n08i40k.streaks.extension.getPeerType
import ru.n08i40k.streaks.extension.isPeerValid
import ru.n08i40k.streaks.extension.isPeerValidOrBot
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.minusDays
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.now
import ru.n08i40k.streaks.extension.plusDays
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.toEpochSeconds
import ru.n08i40k.streaks.extension.toInstant
import ru.n08i40k.streaks.extension.toLocalDate
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.ui.rebuild.RebuildBottomSheet
import ru.n08i40k.streaks.ui.rebuild.UserRebuildState
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RateLimitContext
import ru.n08i40k.streaks.util.fetchPeerUsers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@OptIn(DelicateCoroutinesApi::class)
class StreaksController(
    private val db: PluginDatabase,
    private val timeZonesController: TimeZonesController,
    resourcesProvider: ResourcesProvider,
) {
    companion object {
        private const val MAX_MANUAL_CALENDAR_RESTORES_PER_CHAT = 2
    }

    data class RebuildAllResult(
        val totalChats: Int,
    )

    data class RebuildProgress(
        val peerUser: TLRPC.User,
        val daysChecked: Int,
    )

    data class CalendarInteractionSnapshot(
        val timeZone: TimeZone,
        val streak: Streak?,
        val restoreDays: Set<LocalDate>,
        val manualRestoresUsed: Int,
    )

    sealed class CalendarTapDecision {
        object Ignore : CalendarTapDecision()
        object LimitReached : CalendarTapDecision()

        data class OfferManualRestore(
            val restoreDay: LocalDate,
        ) : CalendarTapDecision()
    }

    enum class AddManualCalendarRestoreResult {
        Added,
        AlreadyExists,
        LimitReached,
    }

    private val rebuildLock = AtomicBoolean(false)

    private val cachedFetcher: ChatHistoryFetcher = CachedChatHistoryFetcher()
    private val remoteFetcher: ChatHistoryFetcher = RemoteChatHistoryFetcher()
    private val streakPopupController = StreakPopupController(db, resourcesProvider)

    private val dao = db.streakDao()
    private val restoreDao = db.streakRestoreDao()

    private suspend fun loadManualRestores(
        ownerUserId: Long,
        peerUserId: Long,
        timeZone: TimeZone,
    ): List<StreakRestore> =
        restoreDao.findManualByRelation(ownerUserId, peerUserId)
            .map { it.copy(restoreDate = it.restoredAt.toLocalDate(timeZone)) }

    private suspend fun persistAutoRestores(
        ownerUserId: Long,
        peerUserId: Long,
        timeZone: TimeZone,
        restoreDates: Set<LocalDate>,
        manualDates: Set<LocalDate>,
    ) {
        val autos = restoreDates.asSequence()
            .filterNot { it in manualDates }
            .map { StreakRestore(ownerUserId, peerUserId, it, it.toInstant(timeZone), manual = false) }
            .toList()

        if (autos.isNotEmpty())
            restoreDao.insertOrReplaceAll(autos)
    }

    private suspend fun removeInvalidPeerStreak(accountId: Int, peerUserId: Long) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        db.withTransaction {
            val streak = dao.findByRelation(ownerUserId, peerUserId)

            dao.deleteByRelation(ownerUserId, peerUserId)

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
        RESTORE,
    }

    private data class DayResult(
        val action: Action,
        val lastOwnerAt: Instant?,
        val lastPeerAt: Instant?,
    )

    private suspend fun fetchStreakActionForDay(
        accountId: Int,
        peerUser: TLRPC.User,
        timeZone: TimeZone,
        day: LocalDate,
        restores: Set<LocalDate>,
        untilRestore: Boolean
    ): DayResult {
        if (restores.contains(day))
            return DayResult(Action.RESTORE, null, null)

        val local = cachedFetcher.fetchActivity(
            accountId,
            peerUser.id,
            timeZone,
            day,
            untilRestore
        )

        when (local.status) {
            is ChatHistoryFetcher.Status.FromBoth -> {
                if (local.status.wasRestored)
                    return DayResult(Action.RESTORE, local.lastOwnerAt, local.lastPeerAt)

                if (!untilRestore)
                    return DayResult(Action.GROW, local.lastOwnerAt, local.lastPeerAt)
            }

            is ChatHistoryFetcher.Status.FromOwner ->
                if (local.status.wasRestored)
                    return DayResult(Action.RESTORE, local.lastOwnerAt, local.lastPeerAt)

            is ChatHistoryFetcher.Status.FromPeer ->
                if (local.status.wasRestored)
                    return DayResult(Action.RESTORE, local.lastOwnerAt, local.lastPeerAt)

            is ChatHistoryFetcher.Status.NoActivity ->
                if (local.status.wasRestored)
                    return DayResult(Action.RESTORE, local.lastOwnerAt, local.lastPeerAt)
        }

        val remote = remoteFetcher.fetchActivity(
            accountId,
            peerUser.id,
            timeZone,
            day,
            untilRestore
        )

        val action = when (remote.status) {
            is ChatHistoryFetcher.Status.FromBoth ->
                if (remote.status.wasRestored) Action.RESTORE else Action.GROW

            is ChatHistoryFetcher.Status.FromOwner ->
                if (remote.status.wasRestored) Action.RESTORE else Action.KILL_BY_PEER

            is ChatHistoryFetcher.Status.FromPeer ->
                if (remote.status.wasRestored) Action.RESTORE else Action.KILL_BY_OWNER

            is ChatHistoryFetcher.Status.NoActivity ->
                if (remote.status.wasRestored) Action.RESTORE else Action.KILL
        }

        return DayResult(action, remote.lastOwnerAt, remote.lastPeerAt)
    }

    // lock and user-facing feedback (notifications, rebuild sheet) are handled by rebuildWithFeedback;
    // callers must hold rebuildLock before calling this
    private suspend fun rebuildOne(
        accountId: Int,
        peerUser: TLRPC.User,
        onProgressUpdate: suspend (progress: RebuildProgress) -> Unit,
    ) {
        try {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val peerUserId = peerUser.id

            val timeZone = timeZonesController.get(ownerUserId, peerUserId)

            val manualRestores = loadManualRestores(ownerUserId, peerUserId, timeZone)
            val manualDates = manualRestores.mapTo(mutableSetOf()) { it.restoreDate }
            val restores = manualDates.toMutableSet()

            val startDay = LocalDate.now(timeZone)
            var currentDay = LocalDate.now(timeZone)

            var startDayIsFrozen = false

            while (true) {
                val checkedDay = currentDay

                val action = fetchStreakActionForDay(
                    accountId,
                    peerUser,
                    timeZone,
                    checkedDay,
                    restores,
                    false
                ).action

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
                            // restore может быть и сегодня
                            val action = fetchStreakActionForDay(
                                accountId,
                                peerUser,
                                timeZone,
                                checkedDay,
                                restores,
                                true
                            ).action

                            if (action == Action.RESTORE) {
                                Logger.info("[StreakRebuild] First-day-restore at ${currentDay.fmt()} for $accountId:$peerUserId")
                                restores.add(checkedDay)
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
                                timeZone,
                                checkedDay.next(),
                                restores,
                                true
                            ).action

                            Logger.info(
                                "[StreakRebuild] Checking if next day was restore. $action at ${
                                    currentDay.next().fmt()
                                } for $accountId:$peerUserId"
                            )

                            if (action == Action.RESTORE) {
                                restores.add(checkedDay.next())
                                currentDay = checkedDay.prev()
                                continue
                            }

                            // устанавливаем rebuildFrom как следующий, ибо restore не было
                            currentDay = checkedDay.next()
                            shouldStop = true
                        }

                    Action.RESTORE -> {
                        // Добавляем restore, что бы след индексация была быстрее
                        restores.add(checkedDay)
                        // Скипаем текущий и предыдущий день, так как сегодня restore, а вчера 100% сдох
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

                sourceStreak
                    ?.takeIf { it.isVisible }
                    ?.let {
                        EventBus.emit(
                            PluginEvent.StreakDeletedEvent(
                                accountId,
                                Clock.System.now(),
                                it
                            )
                        )
                    }

                return
            }

            var sourceStreak: Streak? =
                null

            val boundaryTo =
                fetchStreakActionForDay(accountId, peerUser, timeZone, rebuildTo, restores, false)
            val boundaryFrom =
                if (rebuildFrom == rebuildTo) boundaryTo
                else fetchStreakActionForDay(accountId, peerUser, timeZone, rebuildFrom, restores, false)

            val createdAt =
                listOfNotNull(boundaryFrom.lastOwnerAt, boundaryFrom.lastPeerAt).minOrNull()
                    ?: rebuildFrom.toInstant(timeZone)

            val targetStreak =
                Streak(
                    ownerUserId,
                    peerUserId,
                    createdAt,
                    boundaryTo.lastOwnerAt ?: rebuildTo.toInstant(timeZone),
                    boundaryTo.lastPeerAt ?: rebuildTo.toInstant(timeZone),
                    restores.size,
                    timeZone = timeZone
                )

            db.withTransaction {
                sourceStreak = dao.findByRelation(ownerUserId, peerUserId)

                dao.insertOrReplace(targetStreak)
                restoreDao.insertOrReplaceAll(manualRestores)
                persistAutoRestores(ownerUserId, peerUserId, timeZone, restores, manualDates)
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

    suspend fun rebuild(accountId: Int, peerUser: TLRPC.User) {
        rebuildWithFeedback(accountId, listOf(peerUser))
    }

    suspend fun rebuildAll(
        accountId: Int,
        peers: List<TLRPC.User> = collectEligiblePrivateUsers(accountId),
    ): RebuildAllResult =
        rebuildWithFeedback(accountId, peers)

    private suspend fun rebuildWithFeedback(
        accountId: Int,
        peers: List<TLRPC.User>,
    ): RebuildAllResult {
        if (peers.isEmpty())
            return RebuildAllResult(0)

        if (!rebuildLock.compareAndSet(false, true)) {
            Logger.info("Unable to rebuild for $accountId because another rebuild is already running")
            return RebuildAllResult(0)
        }

        try {
            val states: MutableList<UserRebuildState> =
                peers.map { UserRebuildState.Pending(it) }.toMutableList()

            val sheet = RebuildBottomSheet.launch(RebuildBottomSheet.TYPE_STREAK, states)

            for ((index, peerUser) in peers.withIndex()) {
                var daysChecked = 0

                withContext(RateLimitContext { throttlingClock ->
                    states[index] =
                        UserRebuildState.InProcess(peerUser, daysChecked, throttlingClock)
                    sheet.notifyUserStateChanged(index)
                }) {
                    rebuildOne(accountId, peerUser) { progress ->
                        daysChecked = progress.daysChecked
                        states[index] = UserRebuildState.InProcess(peerUser, daysChecked, null)
                        sheet.notifyUserStateChanged(index)
                    }
                }

                val rebuiltStreak = get(accountId, peerUser.id)
                    ?.takeIf(Streak::isVisible)

                states[index] = UserRebuildState.Done(peerUser, rebuiltStreak)
                sheet.notifyUserStateChanged(index)
            }


            AndroidUtilities.runOnUIThread(sheet::showResults)

            return RebuildAllResult(peers.size)
        } finally {
            rebuildLock.set(false)
        }
    }

    private suspend fun checkForUpdates(
        accountId: Int,
        streak: Streak,
        onProgressUpdate: ((daysChecked: Int, totalDays: Int) -> Unit)? = null,
    ) {
        val timeZone = streak.timeZone

        val now = LocalDate.now(timeZone)

        val updateFromOwnerDay = streak.updateFromOwnerAt.toLocalDate(timeZone)
        val updateFromPeerDay = streak.updateFromPeerAt.toLocalDate(timeZone)

        if (updateFromOwnerDay == updateFromPeerDay && updateFromPeerDay == now)
            return

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerUserId = streak.peerUserId

        val peerUser = MessagesController.getInstance(accountId).getUser(peerUserId) ?: return

        // if last checked day is active, check next
        var currentDay = minOf(updateFromOwnerDay, updateFromPeerDay)
            .let {
                if (updateFromOwnerDay == updateFromPeerDay)
                    it.next()
                else
                    it
            }

        val startDay = currentDay

        val totalDays = (now.toEpochDays() - startDay.toEpochDays() + 1L)
            .coerceAtLeast(0L)
            .toInt()

        // TODO: rename
        var dynStreak = streak

        val manualDates = restoreDao.findManualByRelation(ownerUserId, peerUserId)
            .mapTo(mutableSetOf()) { it.restoreDate }
        val restores = restoreDao.findByRelation(ownerUserId, peerUserId)
            .mapTo(mutableSetOf()) { it.restoreDate }

        while (true) {
            if (currentDay > now)
                break

            val result = fetchStreakActionForDay(
                accountId,
                peerUser,
                timeZone,
                currentDay,
                restores,
                false
            )
            val action = result.action
            val ownerAt = result.lastOwnerAt ?: currentDay.toInstant(timeZone)
            val peerAt = result.lastPeerAt ?: currentDay.toInstant(timeZone)

            when (action) {
                Action.GROW -> {
                    dynStreak = dynStreak.copy(
                        deathNotified = false,
                        warningNotified = false,
                        updateFromOwnerAt = ownerAt,
                        updateFromPeerAt = peerAt
                    )

                    currentDay = currentDay.next()
                }

                Action.RESTORE -> {
                    restores.add(currentDay)

                    dynStreak = dynStreak.copy(
                        deathNotified = false,
                        warningNotified = false,
                        updateFromOwnerAt = ownerAt,
                        updateFromPeerAt = peerAt,
                        restoresCount = restores.size
                    )

                    currentDay = currentDay.next()
                }

                Action.KILL, Action.KILL_BY_OWNER, Action.KILL_BY_PEER -> {
                    if (currentDay == now) {
                        if (action == Action.KILL_BY_OWNER) {
                            dynStreak = dynStreak.copy(
                                updateFromPeerAt = peerAt
                            )
                        } else if (action == Action.KILL_BY_PEER) {
                            dynStreak = dynStreak.copy(
                                updateFromOwnerAt = ownerAt
                            )
                        }

                        onProgressUpdate?.invoke(totalDays, totalDays)
                        break
                    }

                    if (fetchStreakActionForDay(
                            accountId,
                            peerUser,
                            timeZone,
                            currentDay.next(),
                            restores,
                            true
                        ).action == Action.RESTORE
                    ) {
                        val restoreDay = currentDay.next()
                        restores.add(restoreDay)

                        dynStreak = dynStreak.copy(
                            deathNotified = false,
                            warningNotified = false,
                            updateFromOwnerAt = restoreDay.toInstant(timeZone),
                            updateFromPeerAt = restoreDay.toInstant(timeZone),
                            restoresCount = restores.size
                        )

                        // скипнуть текущий день смерти и следующий с restore
                        currentDay = restoreDay.next()
                        continue
                    }

                    if (dynStreak.canRestore) {
                        val resStreak = dynStreak.copy(
                            deathNotified = true,
                            warningNotified = false
                        )

                        db.withTransaction {
                            dao.update(resStreak)
                            persistAutoRestores(
                                ownerUserId,
                                peerUserId,
                                timeZone,
                                restores,
                                manualDates
                            )
                        }

                        if (dynStreak.isVisible && !dynStreak.deathNotified) {
                            EventBus.emit(
                                PluginEvent.StreakLostEvent(
                                    accountId,
                                    currentDay.toInstant(timeZone),
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
                                    currentDay.toInstant(timeZone),
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
                dynStreak.restoresCount != streak.restoresCount

        val lastActiveDay = minOf(
            dynStreak.updateFromOwnerAt.toLocalDate(timeZone),
            dynStreak.updateFromPeerAt.toLocalDate(timeZone)
        )

        val deathEpochSeconds = lastActiveDay.plusDays(2).toEpochSeconds(timeZone)
        val timeUntilDeathSeconds = deathEpochSeconds - System.currentTimeMillis() / 1000L
        val isInWarningWindow = timeUntilDeathSeconds in 1..(8 * 3600)

        dynStreak = dynStreak.copy(warningNotified = isInWarningWindow)

        db.withTransaction {
            dao.update(dynStreak)
            persistAutoRestores(ownerUserId, peerUserId, timeZone, restores, manualDates)
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
    ) {
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
        }
    }

    suspend fun handleUpdate(
        accountId: Int,
        peerUserId: Long,
        at: Instant,
        out: Boolean,
        message: String?,
        sendServiceMessages: Boolean = true
    ) {
        val peerType = getPeerType(accountId, peerUserId)

        // ignore invalid peers
        // продолжение стриков с ботами возможно (остались ли они вообще у кого-нибудь?)
        if (peerType == PeerType.INVALID)
            return

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val streak = get(accountId, peerUserId) ?: run {
            // forbid streak creation for bots
            if (peerType == PeerType.BOT)
                return

            val timeZone = timeZonesController.get(ownerUserId, peerUserId)

            val updateFromOwnerAt: Instant
            val updateFromPeerAt: Instant

            if (out) {
                updateFromOwnerAt = at
                updateFromPeerAt = at.minus(1.days)
            } else {
                updateFromOwnerAt = at.minus(1.days)
                updateFromPeerAt = at
            }

            val streak = Streak(
                ownerUserId,
                peerUserId,
                at,
                updateFromOwnerAt,
                updateFromPeerAt,
                0,
                timeZone = timeZone
            )

            dao.insert(streak)

            EventBus.emit(
                PluginEvent.StreakCreatedEvent(
                    accountId,
                    at,
                    streak
                )
            )

            return
        }

        val timeZone = streak.timeZone
        val now = at.toLocalDate(timeZone)

        if (ServiceMessage.isServiceText(message)) {
            // do not handle restores for bots
            if (peerType == PeerType.VALID
                && message == ServiceMessage.RESTORE_TEXT
                && streak.canRestore
            ) restore( accountId, peerUserId, at, !out )

            return
        }

        if (streak.ended) {
            if (streak.canRestore)
                return

            dao.deleteByRelation(ownerUserId, peerUserId)

            EventBus.emit(
                PluginEvent.StreakDeletedEvent(
                    accountId,
                    at,
                    streak
                )
            )

            return handleUpdate(accountId, peerUserId, at, out, message, sendServiceMessages)
        }

        if (out) {
            if (streak.updateFromOwnerAt.toLocalDate(timeZone) != now) {
                dao.update(
                    streak.copy(
                        updateFromOwnerAt = at,
                        deathNotified = false
                    )
                )
            }
        } else {
            if (streak.updateFromPeerAt.toLocalDate(timeZone) != now) {
                dao.update(
                    streak.copy(
                        updateFromPeerAt = at,
                        deathNotified = false
                    )
                )
            }
        }

        dao.findByRelation(ownerUserId, peerUserId)!!
            .takeIf { it.length > streak.length }
            ?.let {
                EventBus.emit(
                    PluginEvent.StreakGrowUpEvent(
                        accountId,
                        at,
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

        val streak = dao.findByRelation(ownerUserId, peerUserId)
        val timeZone = streak?.timeZone
            ?: timeZonesController.get(ownerUserId, peerUserId)

        val restores = restoreDao.findByRelation(ownerUserId, peerUserId)

        return CalendarInteractionSnapshot(
            timeZone = timeZone,
            streak = streak,
            restoreDays = restores.mapTo(mutableSetOf()) { it.restoreDate },
            manualRestoresUsed = restores.count { it.manual },
        )
    }

    suspend fun analyzeCalendarTap(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
    ): CalendarTapDecision = with(getCalendarInteractionSnapshot(accountId, peerUserId)) {
        if (streak == null)
            return CalendarTapDecision.Ignore

        if (restoreDays.contains(day))
            return CalendarTapDecision.Ignore

        return if (manualRestoresUsed >= MAX_MANUAL_CALENDAR_RESTORES_PER_CHAT)
            CalendarTapDecision.LimitReached
        else
            CalendarTapDecision.OfferManualRestore(day)
    }

    suspend fun addManualCalendarRestore(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate,
    ): AddManualCalendarRestoreResult {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val timeZone = timeZonesController.get(ownerUserId, peerUserId)
        var result = AddManualCalendarRestoreResult.AlreadyExists

        db.withTransaction {
            if (restoreDao.isRestored(ownerUserId, peerUserId, day)) {
                result = AddManualCalendarRestoreResult.AlreadyExists
                return@withTransaction
            }

            if (restoreDao.countManualByRelation(ownerUserId, peerUserId) >=
                MAX_MANUAL_CALENDAR_RESTORES_PER_CHAT
            ) {
                result = AddManualCalendarRestoreResult.LimitReached
                return@withTransaction
            }

            restoreDao.insert(
                StreakRestore(
                    ownerUserId = ownerUserId,
                    peerUserId = peerUserId,
                    restoreDate = day,
                    restoredAt = day.toInstant(timeZone),
                    manual = true,
                )
            )
            result = AddManualCalendarRestoreResult.Added
        }

        return result
    }

    suspend fun get(accountId: Int, peerUserId: Long): Streak? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun exists(accountId: Int, peerUserId: Long): Boolean =
        dao.exists(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun getAllVisible(): List<Streak> = dao.getAll()
        .filter { !it.ended && it.isVisible }

    suspend fun getViewData(accountId: Int, peerUserId: Long): StreakViewData? =
        get(accountId, peerUserId)
            ?.takeIf { !it.ended && it.isVisible }
            ?.let(StreakViewData::from)

    fun getViewDataBlocking(accountId: Int, peerUserId: Long): StreakViewData? =
        runBlocking { getViewData(accountId, peerUserId) }

    private fun buildDebugStreak(
        ownerUserId: Long,
        peerUserId: Long,
        length: Int,
        updateFromOwnerAt: LocalDate,
        updateFromPeerAt: LocalDate,
        timeZone: TimeZone,
        restoresCount: Int = 0,
        deathNotified: Boolean = false,
    ): Streak {
        val minUpdateAt = minOf(updateFromOwnerAt, updateFromPeerAt)

        val createdAt =
            minUpdateAt.minusDays((length + restoresCount - 1).toLong().coerceAtLeast(0L))

        return Streak(
            ownerUserId,
            peerUserId,
            createdAt.toInstant(timeZone),
            updateFromOwnerAt.toInstant(timeZone),
            updateFromPeerAt.toInstant(timeZone),
            restoresCount,
            deathNotified,
            timeZone = timeZone
        )
    }

    suspend fun debugSetThreeDayStreak(accountId: Int, peerUserId: Long): Int {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val timeZone = timeZonesController.get(ownerUserId, peerUserId)
        val now = LocalDate.now(timeZone)
        val streak = buildDebugStreak(ownerUserId, peerUserId, 3, now, now, timeZone)

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

        val upgraded = buildDebugStreak(
            streak.ownerUserId,
            streak.peerUserId,
            nextLevelLength,
            streak.updateFromOwnerAt.toLocalDate(streak.timeZone),
            streak.updateFromPeerAt.toLocalDate(streak.timeZone),
            streak.timeZone,
            restoresCount = streak.restoresCount,
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
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val existing = dao.findByRelation(ownerUserId, peerUserId)

        val timeZone = existing?.timeZone
            ?: timeZonesController.get(ownerUserId, peerUserId)

        val yesterday = LocalDate.now(timeZone).prev()
        val length = existing?.length ?: 3
        val restoresCount = existing?.restoresCount ?: 0

        val streak = buildDebugStreak(
            ownerUserId,
            peerUserId,
            length,
            yesterday,
            yesterday,
            timeZone,
            restoresCount = restoresCount,
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
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val existing = dao.findByRelation(ownerUserId, peerUserId)

        val timeZone = existing?.timeZone
            ?: timeZonesController.get(ownerUserId, peerUserId)

        val deathDay = LocalDate.now(timeZone).minusDays(2)
        val length = existing?.length ?: 3
        val restoresCount = existing?.restoresCount ?: 0

        val streak = buildDebugStreak(
            ownerUserId,
            peerUserId,
            length,
            deathDay,
            deathDay,
            timeZone,
            restoresCount = restoresCount,
            deathNotified = true,
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

    suspend fun delete(accountId: Int, peerUserId: Long): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val streak = dao.findByRelation(ownerUserId, peerUserId)

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
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

    suspend fun restore(
        accountId: Int,
        peerUserId: Long,
        at: Instant,
        byPeer: Boolean = false
    ): Boolean {
        val streak = get(accountId, peerUserId) ?: return false

        if (!streak.canRestore)
            return false

        val restoreDate = at.toLocalDate(streak.timeZone)
        val alreadyRestored =
            restoreDao.isRestored(streak.ownerUserId, streak.peerUserId, restoreDate)

        db.withTransaction {
            dao.update(
                streak.copy(
                    restoresCount = if (alreadyRestored) streak.restoresCount else streak.restoresCount + 1,
                    updateFromOwnerAt = at,
                    updateFromPeerAt = at,
                    deathNotified = false,
                    warningNotified = false,
                )
            )

            if (!alreadyRestored) {
                restoreDao.insert(
                    StreakRestore(
                        streak.ownerUserId,
                        streak.peerUserId,
                        restoreDate,
                        at,
                        manual = false,
                    )
                )
            }
        }

        val restoredStreak = get(accountId, peerUserId)!!

        EventBus.emit(
            PluginEvent.StreakRestoredEvent(
                accountId,
                at,
                restoredStreak,
                byPeer
            )
        )

        if (restoredStreak.length > streak.length) {
            EventBus.emit(
                PluginEvent.StreakGrowUpEvent(
                    accountId,
                    at,
                    streak,
                    restoredStreak
                )
            )
        }

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
