@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.chat_history_fetcher.CachedChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.ChatHistoryFetcher
import ru.n08i40k.streaks.chat_history_fetcher.RemoteChatHistoryFetcher
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.data.StreakPet
import ru.n08i40k.streaks.data.StreakPetTask
import ru.n08i40k.streaks.data.StreakPetTaskPayload
import ru.n08i40k.streaks.data.StreakPetTaskType
import ru.n08i40k.streaks.database.PluginDatabase
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.removeCountBy
import ru.n08i40k.streaks.extension.removeFirstBy
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import ru.n08i40k.streaks.util.Logger
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class StreakPetsController(
    private val logger: Logger,
    private val db: PluginDatabase,
    private val streaksController: StreaksController,
) {
    private val dao = db.streakPetDao()
    private val taskDao = db.streakPetTaskDao()

    private val rebuildLock = AtomicBoolean(false)

    private val cachedFetcher: ChatHistoryFetcher = CachedChatHistoryFetcher()
    private val remoteFetcher: ChatHistoryFetcher = RemoteChatHistoryFetcher()

    suspend fun get(accountId: Int, peerUserId: Long): StreakPet? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

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

    fun isRebuildRunning(): Boolean = rebuildLock.get()

    private suspend fun rebuildInTransaction(
        accountId: Int,
        peer: TLRPC.User,
        onProgressUpdate: (progress: RebuildProgress) -> Unit
    ) {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val peerUserId = peer.id

        val name = dao.findByRelation(ownerUserId, peerUserId)!!.name
        dao.deleteByRelation(ownerUserId, peerUserId)

        val startDay = LocalDate.now()
        val endDay = streaksController.get(accountId, peerUserId)!!.createdAt

        var currentDay = startDay

        val tasks = mutableSetOf<StreakPetTask>()

        while (true) {
            if (currentDay < endDay)
                break

            val ids = cachedFetcher.fetchIds(accountId, peerUserId, currentDay)
                .ifEmpty { remoteFetcher.fetchIds(accountId, peerUserId, currentDay) }
                .toMutableList()

            if (ids.isEmpty()) {
                currentDay = currentDay.prev()
                continue
            }

            for (task in StreakPetTask.getNewTasksList(ownerUserId, peerUserId, currentDay)) {
                val payload = when (task.type) {
                    StreakPetTaskType.EXCHANGE_ONE_MESSAGE -> {
                        StreakPetTaskPayload.ExchangeOneMessage(
                            ids.removeFirstBy { (_, out) -> out }?.first,
                            ids.removeFirstBy { (_, out) -> !out }?.first,
                        )
                    }

                    StreakPetTaskType.SEND_FOUR_MESSAGES_EACH -> {
                        val fromOwner = ids.removeCountBy(4) { (_, out) -> out }
                        val fromPeer = ids.removeCountBy(4) { (_, out) -> !out }

                        StreakPetTaskPayload.SendFourMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.first,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.first,
                        )
                    }

                    StreakPetTaskType.SEND_TEN_MESSAGES_EACH -> {
                        val fromOwner = ids.removeCountBy(10) { (_, out) -> out }
                        val fromPeer = ids.removeCountBy(10) { (_, out) -> !out }

                        StreakPetTaskPayload.SendTenMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.first,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.first,
                        )
                    }
                }

                tasks.add(task.copy(dbPayload = payload, isCompleted = payload.isCompleted))
            }

            onProgressUpdate(
                RebuildProgress(
                    user = peer,
                    daysChecked = (startDay.toEpochDay() - currentDay.toEpochDay()).toInt() + 1
                )
            )

            currentDay = currentDay.prev()
        }

        val points = tasks.sumOf { if (it.isCompleted) it.type.points else 0 }

        dao.insertAll(
            StreakPet(
                ownerUserId,
                peerUserId,
                endDay,
                startDay,
                name,
                points
            )
        )

        tasks.forEach { taskDao.insertOrUpdateAll(it) }
    }

    // do not call this func if streak pet is not existing
    suspend fun rebuild(
        accountId: Int,
        peer: TLRPC.User,
        onProgressUpdate: (progress: RebuildProgress) -> Unit,
    ) {
        if (!rebuildLock.compareAndSet(false, true)) {
            logger.info("Unable to rebuild peer $accountId:${peer.id} because another rebuild is already running")
            return
        }

        try {
            db.withTransaction { rebuildInTransaction(accountId, peer, onProgressUpdate) }
        } catch (e: Throwable) {
            logger.fatal("Failed to rebuild peer $accountId:${peer.id}", e)
        } finally {
            rebuildLock.set(false)
        }
    }

    private suspend fun checkForUpdates(
        accountId: Int,
        streakPet: StreakPet,
        notCompletedTasks: List<StreakPetTask>
    ) {
        val lastCheckedDay = streakPet.lastCheckedAt
        val now = LocalDate.now()

        val peerUserId = streakPet.peerUserId

        var currentDay = lastCheckedDay

        val tasks = mutableListOf<StreakPetTask>()

        while (true) {
            if (currentDay > now)
                break

            val ids = cachedFetcher.fetchIds(accountId, peerUserId, currentDay)
                .ifEmpty { remoteFetcher.fetchIds(accountId, peerUserId, currentDay) }
                .toMutableList()

            if (ids.isEmpty()) {
                currentDay = currentDay.prev()
                continue
            }

            for (task in notCompletedTasks) {
                if (task.createdAt != currentDay)
                    continue

                val payload = when (task.type) {
                    StreakPetTaskType.EXCHANGE_ONE_MESSAGE -> {
                        StreakPetTaskPayload.ExchangeOneMessage(
                            ids.removeFirstBy { (_, out) -> out }?.first,
                            ids.removeFirstBy { (_, out) -> !out }?.first,
                        )
                    }

                    StreakPetTaskType.SEND_FOUR_MESSAGES_EACH -> {
                        val fromOwner = ids.removeCountBy(4) { (_, out) -> out }
                        val fromPeer = ids.removeCountBy(4) { (_, out) -> !out }

                        StreakPetTaskPayload.SendFourMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.first,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.first,
                        )
                    }

                    StreakPetTaskType.SEND_TEN_MESSAGES_EACH -> {
                        val fromOwner = ids.removeCountBy(10) { (_, out) -> out }
                        val fromPeer = ids.removeCountBy(10) { (_, out) -> !out }

                        StreakPetTaskPayload.SendTenMessagesEach(
                            fromOwner.size,
                            fromOwner.lastOrNull()?.first,
                            fromPeer.size,
                            fromPeer.lastOrNull()?.first,
                        )
                    }
                }

                tasks.add(task.copy(dbPayload = payload, isCompleted = payload.isCompleted))
            }

            currentDay = currentDay.next()
        }

        val points = tasks.sumOf { if (it.isCompleted) it.type.points else 0 }

        db.withTransaction {
            dao.update(streakPet.copy(lastCheckedAt = now, points = streakPet.points + points))

            tasks.forEach { taskDao.insertOrUpdateAll(it) }
        }
    }

    suspend fun checkAllForUpdates() {
        val now = LocalDate.now()

        for (accountId in userConfigAuthorizedIds) {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId

            val streakPets = dao.findAllByOwnerUserId(ownerUserId)

            for (streakPet in streakPets) {
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

                if (notCompletedTasks.isEmpty()) {
                    if (streakPet.lastCheckedAt != now)
                        dao.update(streakPet.copy(lastCheckedAt = now))

                    continue
                }

                checkForUpdates(accountId, streakPet, notCompletedTasks)
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
        val streakPet = get(accountId, peerUserId)
            ?: run {
                // !out because if it is true, user already accepted and created streak-pet locally
                if (!out && message == ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
                    return@run create(accountId, peerUserId, at).streakPet
                else
                    return@run null
            }
            ?: return

        ServiceMessage.PET_SET_NAME_REGEX.matchEntire(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                rename(accountId, peerUserId, it)
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

        val notCompletedTasks = enumValues<StreakPetTaskType>()
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

        val lastCheckedAt =
            if (streakPet.lastCheckedAt < targetDay) targetDay else streakPet.lastCheckedAt
        val pointsToAdd = if (updatedTask?.isCompleted == true) updatedTask.type.points else 0

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

    sealed class CreateResult(val streakPet: StreakPet) {
        class Created(streakPet: StreakPet) : CreateResult(streakPet)
        class AlreadyExists(streakPet: StreakPet) : CreateResult(streakPet)
    }

    suspend fun create(
        accountId: Int,
        peerUserId: Long,
        at: LocalDate = LocalDate.now()
    ): CreateResult {
        get(accountId, peerUserId)?.let { return CreateResult.AlreadyExists(it) }

        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

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
            dao.insertAll(streakPet)

            var currentDay = at

            while (true) {
                if (currentDay > LocalDate.now())
                    break

                StreakPetTask.getNewTasksList(ownerUserId, peerUserId, currentDay)
                    .forEach { taskDao.insertIfNotExistsAll(it) }

                currentDay = currentDay.next()
            }
        }

        return CreateResult.Created(streakPet)
    }

    suspend fun rename(accountId: Int, peerUserId: Long, newName: String): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val pet = dao.findByRelation(ownerUserId, peerUserId) ?: return false

        val normalizedName = newName.trim().take(20)
        if (normalizedName.isEmpty())
            return false

        dao.update(pet.copy(name = normalizedName))
        return true
    }

    suspend fun delete(accountId: Int, peerUserId: Long): Boolean {
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId

        if (dao.findByRelation(ownerUserId, peerUserId) == null)
            return false

        dao.deleteByRelation(ownerUserId, peerUserId)

        return true
    }
}
