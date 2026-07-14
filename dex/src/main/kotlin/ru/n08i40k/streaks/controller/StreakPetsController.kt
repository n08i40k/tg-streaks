package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import kotlinx.coroutines.withContext
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.chat_history_fetcher.CachedChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.RemoteChatHistoryFetcher
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.data.StreakPet
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.data.StreakPetTaskPayload
import ru.n08i40k.streaks.data.StreakPetTaskType
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.event.EventBus
import ru.n08i40k.streaks.event.PluginEvent
import ru.n08i40k.streaks.extension.PeerType
import ru.n08i40k.streaks.extension.getPeerType
import ru.n08i40k.streaks.extension.isPeerValidOrBot
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.now
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.removeCountBy
import ru.n08i40k.streaks.extension.removeFirstBy
import ru.n08i40k.streaks.exception.InvalidPeerException
import ru.n08i40k.streaks.ui.rebuild.RebuildBottomSheet
import ru.n08i40k.streaks.ui.rebuild.UserRebuildState
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RateLimitContext
import ru.n08i40k.streaks.util.fetchPeerUsers
import kotlinx.datetime.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Instant

class StreakPetsController(
    private val db: PluginDatabase,
    private val streaksController: StreaksController,
) {
    private val dao = db.streakPetDao()
    private val taskDao = db.streakPetTaskDao()

    private val rebuildLock = AtomicBoolean(false)

    private val cachedFetcher: ChatHistoryFetcher = CachedChatHistoryFetcher()
    private val remoteFetcher: ChatHistoryFetcher = RemoteChatHistoryFetcher()

    private suspend fun removeInvalidPeerPet(accountId: Int, peerUserId: Long) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        db.withTransaction {
            val pet = dao.findByRelation(ownerUserId, peerUserId)

            dao.deleteByRelation(ownerUserId, peerUserId)
            taskDao.deleteByRelation(ownerUserId, peerUserId)

            pet?.let {
                EventBus.emit(
                    PluginEvent.StreakPetDeletedEvent(
                        accountId,
                        Clock.System.now(),
                        it,
                        PluginEvent.StreakPetDeletedEvent.By.PLUGIN
                    )
                )
            }
        }

        Logger.info("Removed streak-pet for invalid peer $accountId:$peerUserId after PEER_ID_INVALID")
    }

    suspend fun get(accountId: Int, peerUserId: Long): StreakPet? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    fun isFabEnabled(accountId: Int, peerUserId: Long): Boolean? =
        dao.isFabEnabled(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun setFabEnabled(accountId: Int, peerUserId: Long, enabled: Boolean) {
        val streakPet = get(accountId, peerUserId)
            ?: throw NullPointerException("Unable to toggle streak pet fab state because it doesn't exists for $accountId:$peerUserId")

        dao.updateFabEnabled(UserConfig.getInstance(accountId).clientUserId, peerUserId, enabled)

        EventBus.emit(
            PluginEvent.StreakPetFabStateChanged(
                accountId,
                Clock.System.now(),
                streakPet.copy(fabEnabled = enabled),
                enabled
            )
        )
    }

    data class ViewStateSnapshot(
        val pet: StreakPet,
        val streak: ru.n08i40k.streaks.data.Streak?,
        val ownerUser: TLRPC.User?,
        val peerUser: TLRPC.User?,
        val tasks: List<StreakPetTask>,
    )

    suspend fun getViewStateSnapshot(
        accountId: Int,
        peerUserId: Long,
        day: LocalDate = LocalDate.now(),
    ): ViewStateSnapshot? {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val pet = dao.findByRelation(ownerUserId, peerUserId) ?: return null

        val taskByType = taskDao.findAllByRelationAndDay(ownerUserId, peerUserId, day)
            .associateBy { it.type }
            .toMutableMap()

        StreakPetTask.getNewTasksList(ownerUserId, peerUserId, day)
            .forEach { taskByType.putIfAbsent(it.type, it) }

        val messagesController = MessagesController.getInstance(accountId)

        return ViewStateSnapshot(
            pet = pet,
            streak = streaksController.get(accountId, peerUserId),
            ownerUser = messagesController.getUser(ownerUserId),
            peerUser = messagesController.getUser(peerUserId),
            tasks = StreakPetTaskType.entries.mapNotNull(taskByType::get),
        )
    }

    data class RebuildProgress(
        val peerUser: TLRPC.User,
        val daysChecked: Int,
    )

    fun isRebuildRunning(): Boolean = rebuildLock.get()

    private suspend fun rebuildInTransaction(
        accountId: Int,
        peerUser: TLRPC.User,
        onProgressUpdate: suspend (progress: RebuildProgress) -> Unit
    ): Pair<StreakPet?, StreakPet> {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerUserId = peerUser.id

        val sourcePet = dao.findByRelation(ownerUserId, peerUserId)!!
        val name = sourcePet.name

        val startDay = LocalDate.now()
        val endDay = streaksController.get(accountId, peerUserId)!!.createdAt

        var currentDay = startDay

        val tasks = mutableSetOf<StreakPetTask>()
        val maxPerSide = StreakPetTask.MAX_PER_SIDE

        while (true) {
            if (currentDay < endDay)
                break

            val messages = cachedFetcher.fetchRawMessages(
                accountId,
                peerUserId,
                currentDay,
                maxPerSide,
                maxPerSide
            )
                .ifEmpty {
                    remoteFetcher.fetchRawMessages(
                        accountId,
                        peerUserId,
                        currentDay,
                        maxPerSide,
                        maxPerSide
                    )
                }
                .filterNot { ServiceMessage.isServiceText(it.message) }
                .toMutableList()

            if (messages.isEmpty()) {
                currentDay = currentDay.prev()
                continue
            }

            for (task in StreakPetTask.getNewTasksList(ownerUserId, peerUserId, currentDay)) {
                val payload = when (task.type) {
                    StreakPetTaskType.EXCHANGE_ONE_MESSAGE -> {
                        StreakPetTaskPayload.ExchangeOneMessage(
                            messages.removeFirstBy { it.out }?.id,
                            messages.removeFirstBy { !it.out }?.id,
                        )
                    }

                    StreakPetTaskType.SEND_FOUR_MESSAGES_EACH -> {
                        val fromOwner = messages.removeCountBy(4) { it.out }
                        val fromPeer = messages.removeCountBy(4) { !it.out }

                        StreakPetTaskPayload.SendFourMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.id,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.id,
                        )
                    }

                    StreakPetTaskType.SEND_TEN_MESSAGES_EACH -> {
                        val fromOwner = messages.removeCountBy(10) { it.out }
                        val fromPeer = messages.removeCountBy(10) { !it.out }

                        StreakPetTaskPayload.SendTenMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.id,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.id,
                        )
                    }
                }

                tasks.add(task.copy(dbPayload = payload, isCompleted = payload.isCompleted))
            }

            onProgressUpdate(
                RebuildProgress(
                    peerUser = peerUser,
                    daysChecked = (startDay.toEpochDays() - currentDay.toEpochDays()).toInt() + 1
                )
            )

            currentDay = currentDay.prev()
        }

        val points = tasks.sumOf { if (it.isCompleted) it.type.points else 0 }

        val targetPet = StreakPet(
            ownerUserId,
            peerUserId,
            endDay,
            startDay,
            name,
            points
        )

        db.withTransaction {
            dao.deleteByRelation(ownerUserId, peerUserId)
            dao.insert(targetPet)

            tasks.forEach { taskDao.insertOrUpdateAll(it) }
        }

        return sourcePet to targetPet
    }

    // do not call this func if streak pet is not existing
    // lock and user-facing feedback (notifications, rebuild sheet) are handled by rebuild;
    // callers must hold rebuildLock before calling this
    private suspend fun rebuildOne(
        accountId: Int,
        peerUser: TLRPC.User,
        onProgressUpdate: suspend (progress: RebuildProgress) -> Unit,
    ) {
        try {
            val (sourcePet, targetPet) = rebuildInTransaction(accountId, peerUser, onProgressUpdate)

            EventBus.emit(
                PluginEvent.StreakPetRebuiltEvent(
                    accountId,
                    Clock.System.now(),
                    sourcePet,
                    targetPet
                )
            )
        } catch (_: InvalidPeerException) {
            removeInvalidPeerPet(accountId, peerUser.id)
        } catch (e: Throwable) {
            Logger.fatal("Failed to rebuild peer $accountId:${peerUser.id}", e)
        }
    }

    // do not call this func if streak pet is not existing
    suspend fun rebuild(accountId: Int, peerUser: TLRPC.User) {
        if (!rebuildLock.compareAndSet(false, true)) {
            Logger.info("Unable to rebuild peer $accountId:${peerUser.id} because another rebuild is already running")
            return
        }

        try {
            val states: MutableList<UserRebuildState> =
                mutableListOf(UserRebuildState.Pending(peerUser))
            val sheet = RebuildBottomSheet.launch(RebuildBottomSheet.TYPE_STREAK_PET, states)

            var daysChecked = 0

            withContext(RateLimitContext { throttlingClock ->
                states[0] = UserRebuildState.InProcess(peerUser, daysChecked, throttlingClock)
                sheet.notifyUserStateChanged(0)
            }) {
                rebuildOne(accountId, peerUser) { progress ->
                    daysChecked = progress.daysChecked
                    states[0] = UserRebuildState.InProcess(peerUser, daysChecked, null)
                    sheet.notifyUserStateChanged(0)
                }
            }

            val rebuiltPet = get(accountId, peerUser.id)
            states[0] = UserRebuildState.Done(peerUser, rebuiltPet)
            sheet.notifyUserStateChanged(0)
            sheet.let { AndroidUtilities.runOnUIThread(it::showResults) }
        } finally {
            rebuildLock.set(false)
        }
    }

    private suspend fun checkForUpdates(
        accountId: Int,
        streakPet: StreakPet,
        notCompletedTasks: List<StreakPetTask>
    ) {
        var currentPet = streakPet

        val lastCheckedDay = currentPet.lastCheckedAt
        val now = LocalDate.now()

        val peerUserId = currentPet.peerUserId

        var currentDay = lastCheckedDay

        val tasks = mutableListOf<StreakPetTask>()

        var waitForRenew = false

        while (true) {
            if (currentDay > now)
                break

            val fromOwnerMax = notCompletedTasks
                .filter { it.createdAt == currentDay }
                .sumOf {
                    when (it.payload) {
                        is StreakPetTaskPayload.ExchangeOneMessage -> it.payload.remainingFromOwner
                        is StreakPetTaskPayload.SendFourMessagesEach -> it.payload.remainingFromOwner
                        is StreakPetTaskPayload.SendTenMessagesEach -> it.payload.remainingFromOwner
                    }
                }

            val fromPeerMax = notCompletedTasks
                .filter { it.createdAt == currentDay }
                .sumOf {
                    when (it.payload) {
                        is StreakPetTaskPayload.ExchangeOneMessage -> it.payload.remainingFromPeer
                        is StreakPetTaskPayload.SendFourMessagesEach -> it.payload.remainingFromPeer
                        is StreakPetTaskPayload.SendTenMessagesEach -> it.payload.remainingFromPeer
                    }
                }

            val messages = cachedFetcher.fetchRawMessages(
                accountId,
                peerUserId,
                currentDay,
                fromOwnerMax,
                fromPeerMax
            )
                .ifEmpty {
                    remoteFetcher.fetchRawMessages(
                        accountId,
                        peerUserId,
                        currentDay,
                        fromOwnerMax,
                        fromPeerMax
                    )
                }
                .sortedBy { it.id }
                .filterNot { message ->
                    if (!ServiceMessage.isServiceText(message.message))
                        return@filterNot false

                    // if deleted by any of sides - set flag to remove pet at the end of check
                    if (message.message == ServiceMessage.PET_DELETED_TEXT) {
                        waitForRenew = true
                        return@filterNot true
                    }

                    // if renewed after deleting - reset flag
                    if (message.message == ServiceMessage.PET_INVITE_ACCEPTED_TEXT) {
                        waitForRenew = false
                        return@filterNot true
                    }

                    ServiceMessage.PET_SET_NAME_REGEX.matchEntire(message.message.orEmpty())
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            currentPet = currentPet.copy(name = it)
                            dao.update(currentPet)
                            return@filterNot true
                        }

                    true
                }
                .toMutableList()

            if (messages.isEmpty()) {
                currentDay = currentDay.next()
                continue
            }

            for (task in notCompletedTasks) {
                if (task.createdAt != currentDay)
                    continue

                val payload = when (task.type) {
                    StreakPetTaskType.EXCHANGE_ONE_MESSAGE -> {
                        StreakPetTaskPayload.ExchangeOneMessage(
                            messages.removeFirstBy { it.out }?.id,
                            messages.removeFirstBy { !it.out }?.id,
                        )
                    }

                    StreakPetTaskType.SEND_FOUR_MESSAGES_EACH -> {
                        val fromOwner = messages.removeCountBy(4) { it.out }
                        val fromPeer = messages.removeCountBy(4) { !it.out }

                        StreakPetTaskPayload.SendFourMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.id,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.id,
                        )
                    }

                    StreakPetTaskType.SEND_TEN_MESSAGES_EACH -> {
                        val fromOwner = messages.removeCountBy(10) { it.out }
                        val fromPeer = messages.removeCountBy(10) { !it.out }

                        StreakPetTaskPayload.SendTenMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.id,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.id,
                        )
                    }
                }

                tasks.add(task.copy(dbPayload = payload, isCompleted = payload.isCompleted))
            }

            currentDay = currentDay.next()
        }

        val points = tasks.sumOf { if (it.isCompleted) it.type.points else 0 }

        db.withTransaction {
            with(currentPet) {
                if (waitForRenew) {
                    dao.deleteByRelation(ownerUserId, peerUserId)
                    taskDao.deleteByRelation(ownerUserId, peerUserId)
                } else {
                    dao.update(copy(lastCheckedAt = now, points = this.points + points))
                    tasks.forEach { taskDao.insertOrUpdateAll(it) }
                }
            }
        }
    }

    suspend fun checkAllForUpdates(accountId: Int) {
        val now = LocalDate.now()
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val streakPets = dao.findAllByOwnerUserId(ownerUserId)

        for (streakPet in streakPets) {
            try {
                if (
                    taskDao.findAllByRelationAndDay(ownerUserId, streakPet.peerUserId, now)
                        .isEmpty()
                ) {
                    taskDao.insertIfNotExistsAll(
                        *StreakPetTask.getNewTasksList(ownerUserId, streakPet.peerUserId, now)
                            .toTypedArray()
                    )
                }

                val notCompletedTasks =
                    taskDao.findNotCompletedByRelationAndDay(
                        ownerUserId,
                        streakPet.peerUserId,
                        streakPet.lastCheckedAt
                    ).toMutableList()

                run {
                    var currentDay = streakPet.lastCheckedAt.next()

                    while (true) {
                        if (currentDay > now)
                            break

                        notCompletedTasks.addAll(
                            StreakPetTask.getNewTasksList(
                                ownerUserId,
                                streakPet.peerUserId,
                                currentDay
                            )
                        )

                        currentDay = currentDay.next()
                    }
                }

                checkForUpdates(accountId, streakPet, notCompletedTasks)
            } catch (_: InvalidPeerException) {
                removeInvalidPeerPet(accountId, streakPet.peerUserId)
            }
        }
    }

    suspend fun handleUpdate(
        accountId: Int,
        peerUserId: Long,
        at: LocalDate,
        messageId: Int,
        message: String?,
        out: Boolean
    ) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerType = getPeerType(accountId, peerUserId)

        if (peerType == PeerType.INVALID)
            return

        val streakPet = dao.findByRelation(ownerUserId, peerUserId)
            ?: run {
                // !out because if it is true, user already accepted and created streak-pet locally
                if (peerType == PeerType.VALID && !out && message == ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
                    create(accountId, peerUserId, at, true)

                return
            }

        if (message == ServiceMessage.PET_DELETED_TEXT) {
            dao.deleteByRelation(ownerUserId, peerUserId)
            taskDao.deleteByRelation(ownerUserId, peerUserId)

            EventBus.emit(
                PluginEvent.StreakPetDeletedEvent(
                    accountId,
                    Clock.System.now(),
                    streakPet,
                    if (out)
                        PluginEvent.StreakPetDeletedEvent.By.SELF_MESSAGE
                    else
                        PluginEvent.StreakPetDeletedEvent.By.PEER_MESSAGE
                )
            )

            return
        }

        ServiceMessage.PET_SET_NAME_REGEX.matchEntire(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                rename(accountId, peerUserId, it, false, !out)
                return
            }

        if (ServiceMessage.isServiceText(message))
            return

        val now = LocalDate.now()
        val targetDay = if (at > now) now else at

        val tasksByType = taskDao.findAllByRelationAndDay(
            ownerUserId,
            peerUserId,
            targetDay
        ).associateBy { it.type }.toMutableMap()

        val missingTasksForTargetDay = mutableListOf<StreakPetTask>()

        StreakPetTask.getNewTasksList(ownerUserId, peerUserId, targetDay)
            .forEach {
                if (tasksByType.putIfAbsent(it.type, it) == null) {
                    missingTasksForTargetDay.add(it)
                }
            }

        val notCompletedTasks = StreakPetTaskType.entries
            .mapNotNull(tasksByType::get)
            .filterNot { it.isCompleted }

        val backfillTasks = mutableListOf<StreakPetTask>()
        run {
            var currentDay = streakPet.lastCheckedAt.next()

            while (true) {
                if (currentDay > targetDay)
                    break

                backfillTasks.addAll(
                    StreakPetTask.getNewTasksList(
                        ownerUserId,
                        streakPet.peerUserId,
                        currentDay
                    )
                )

                currentDay = currentDay.next()
            }
        }

        var updatedTask: StreakPetTask? = null

        for (task in notCompletedTasks) {
            var updated = false

            val payload = when (val oldPayload = task.payload) {
                is StreakPetTaskPayload.ExchangeOneMessage -> {
                    if (out && oldPayload.fromOwnerMessageId == null) {
                        updated = true
                        oldPayload.copy(fromOwnerMessageId = messageId)
                    } else if (!out && oldPayload.fromPeerMessageId == null) {
                        updated = true
                        oldPayload.copy(fromPeerMessageId = messageId)
                    } else oldPayload
                }

                is StreakPetTaskPayload.SendFourMessagesEach -> {
                    if (out && oldPayload.fromOwnerMessagesCount < 4) {
                        updated = true
                        oldPayload.copy(
                            fromOwnerMessagesCount = oldPayload.fromOwnerMessagesCount + 1,
                            fromOwnerLastMessageId = messageId
                        )
                    } else if (!out && oldPayload.fromPeerMessagesCount < 4) {
                        updated = true
                        oldPayload.copy(
                            fromPeerMessagesCount = oldPayload.fromPeerMessagesCount + 1,
                            fromPeerLastMessageId = messageId
                        )
                    } else oldPayload
                }

                is StreakPetTaskPayload.SendTenMessagesEach -> {
                    if (out && oldPayload.fromOwnerMessagesCount < 10) {
                        updated = true
                        oldPayload.copy(
                            fromOwnerMessagesCount = oldPayload.fromOwnerMessagesCount + 1,
                            fromOwnerLastMessageId = messageId
                        )
                    } else if (!out && oldPayload.fromPeerMessagesCount < 10) {
                        updated = true
                        oldPayload.copy(
                            fromPeerMessagesCount = oldPayload.fromPeerMessagesCount + 1,
                            fromPeerLastMessageId = messageId
                        )
                    } else oldPayload
                }
            }

            if (updated) {
                updatedTask = task.copy(dbPayload = payload, isCompleted = payload.isCompleted)
                break
            }
        }

        val lastCheckedAt = maxOf(streakPet.lastCheckedAt, targetDay)

        val pointsToAdd =
            if (updatedTask?.isCompleted == true)
                updatedTask.type.points
            else
                0

        if (updatedTask == null && backfillTasks.isEmpty() && lastCheckedAt == streakPet.lastCheckedAt)
            return

        db.withTransaction {
            if (missingTasksForTargetDay.isNotEmpty())
                taskDao.insertIfNotExistsAll(*missingTasksForTargetDay.toTypedArray())

            if (backfillTasks.isNotEmpty())
                taskDao.insertIfNotExistsAll(*backfillTasks.toTypedArray())

            dao.update(
                streakPet.copy(
                    lastCheckedAt = lastCheckedAt,
                    points = streakPet.points + pointsToAdd
                )
            )

            if (updatedTask != null)
                taskDao.insertOrUpdateAll(updatedTask)
        }
    }

    suspend fun create(
        accountId: Int,
        peerUserId: Long,
        at: LocalDate = LocalDate.now(),
        byInvite: Boolean = false
    ): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        dao.findByRelation(ownerUserId, peerUserId)?.let { return false }

        val messagesController = MessagesController.getInstance(accountId)
        val ownerUser = messagesController.getUser(ownerUserId)
        val peerUser = messagesController.getUser(peerUserId)

        val streakPet = StreakPet(
            ownerUserId,
            peerUserId,
            at,
            at,
            "${ownerUser.label}&${peerUser.label}",
            0
        )

        db.withTransaction {
            dao.insert(streakPet)

            var currentDay = at

            while (true) {
                if (currentDay > LocalDate.now())
                    break

                StreakPetTask.getNewTasksList(ownerUserId, peerUserId, currentDay)
                    .forEach { taskDao.insertIfNotExistsAll(it) }

                currentDay = currentDay.next()
            }
        }

        EventBus.emit(
            PluginEvent.StreakPetCreatedEvent(
                accountId,
                Clock.System.now(),
                streakPet,
                byInvite
            )
        )

        return true
    }

    suspend fun rename(
        accountId: Int,
        peerUserId: Long,
        newName: String,
        byPlugin: Boolean,
        byPeer: Boolean,
    ): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val pet = dao.findByRelation(ownerUserId, peerUserId)
            ?: return false

        val normalizedName = newName.trim().take(20)

        if (normalizedName.isEmpty())
            return false

        val renamed = pet.copy(name = normalizedName)
        dao.update(renamed)

        EventBus.emit(
            PluginEvent.StreakPetRenamedEvent(
                accountId,
                Clock.System.now(),
                renamed,
                if (byPlugin)
                    PluginEvent.StreakPetRenamedEvent.By.SELF
                else if (!byPeer)
                    PluginEvent.StreakPetRenamedEvent.By.SELF_MESSAGE
                else
                    PluginEvent.StreakPetRenamedEvent.By.PEER_MESSAGE
            )
        )

        return true
    }

    suspend fun delete(
        accountId: Int,
        peerUserId: Long,
        at: Instant = Clock.System.now(),
        byPlugin: Boolean = false,
    ): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        val pet = dao.findByRelation(ownerUserId, peerUserId)
            ?: return false

        dao.deleteByRelation(ownerUserId, peerUserId)
        taskDao.deleteByRelation(ownerUserId, peerUserId)

        EventBus.emit(
            PluginEvent.StreakPetDeletedEvent(
                accountId,
                at,
                pet,
                if (byPlugin)
                    PluginEvent.StreakPetDeletedEvent.By.PLUGIN
                else
                    PluginEvent.StreakPetDeletedEvent.By.SELF
            )
        )

        return true
    }

    suspend fun pruneInvalid(accountId: Int) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val pets = dao.findAllByOwnerUserId(ownerUserId)

        val peerUsers = fetchPeerUsers(
            accountId,
            ArrayList(pets.map { it.peerUserId })
        ) ?: return

        pets
            .filterNot { isPeerValidOrBot(peerUsers[it.peerUserId]) }
            .forEach { removeInvalidPeerPet(accountId, it.peerUserId) }
    }
}
