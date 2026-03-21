@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.controller

import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
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
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.prev
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.MyResult
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.comparisons.compareBy

@OptIn(DelicateCoroutinesApi::class)
class StreaksController(
    private val db: PluginDatabase,
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

    data class ServiceMessagePolicy(
        val sendCreation: Boolean,
        val sendUpgrade: Boolean,
        val sendDeath: Boolean,
        val sendRestore: Boolean,
    ) {
        companion object {
            val LIVE = ServiceMessagePolicy(
                sendCreation = true,
                sendUpgrade = true,
                sendDeath = true,
                sendRestore = true,
            )

            val REBUILD = ServiceMessagePolicy(
                sendCreation = false,
                sendUpgrade = false,
                sendDeath = false,
                sendRestore = false,
            )

            val UPDATE_CHECK = ServiceMessagePolicy(
                sendCreation = false,
                sendUpgrade = true,
                sendDeath = true,
                sendRestore = false,
            )
        }
    }

    data class RebuildProgress(
        val peerLabel: String,
        val daysChecked: Int,
    ) {
        fun showBulletin() {
            val plugin = Plugin.getInstance() ?: return
            val message = plugin.translate(
                TranslationKey.FORCE_CHECK_DAY_PROGRESS_CHAT,
                mapOf(
                    "peer_name" to peerLabel,
                    "days_checked" to daysChecked.toString(),
                )
            )

            AndroidUtilities.runOnUIThread {
                org.telegram.ui.Components.Bulletin.hideVisible()
                plugin.showBulletin("msg_retry", message)
            }
        }
    }

    private val rebuildLock = AtomicBoolean(false)

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

    private fun getPeerLabel(peer: TLRPC.User): String =
        peer.username
            ?.takeIf { it.isNotBlank() }
            ?.let { "@$it" }
            ?: UserObject.getUserName(peer).takeIf { it.isNotBlank() }
            ?: peer.id.toString()

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
        val ownerUserId = UserConfig.getInstance(accountId).clientUserId
        val messagesController = MessagesController.getInstance(accountId)
        val usersById = LinkedHashMap<Long, TLRPC.User>()
        val dialogs = messagesController.getDialogs(0)

        dialogs?.forEach { dialog ->
            if (dialog == null)
                return@forEach

            val dialogId = resolveDialogId(dialog)

            if (dialogId <= 0L || !DialogObject.isUserDialog(dialogId))
                return@forEach

            if (
                dialogId == ownerUserId
                || UserObject.isReplyUser(dialogId)
                || UserObject.isService(dialogId)
            ) {
                return@forEach
            }

            val user = messagesController.getUser(dialogId) ?: return@forEach

            if (
                UserObject.isUserSelf(user)
                || UserObject.isDeleted(user)
                || UserObject.isBot(user)
                || UserObject.isReplyUser(user)
            ) {
                return@forEach
            }

            usersById[user.id] = user
        }

        if (usersById.isNotEmpty())
            return usersById.values.toList()

        val dialogsDict = messagesController.dialogs_dict

        for (index in 0 until dialogsDict.size()) {
            val dialog = dialogsDict.valueAt(index) as? TLRPC.Dialog ?: continue
            val dialogId = resolveDialogId(dialog)

            if (dialogId <= 0L || !DialogObject.isUserDialog(dialogId))
                continue

            if (
                dialogId == ownerUserId
                || UserObject.isReplyUser(dialogId)
                || UserObject.isService(dialogId)
            ) {
                continue
            }

            val user = messagesController.getUser(dialogId) ?: continue

            if (
                UserObject.isUserSelf(user)
                || UserObject.isDeleted(user)
                || UserObject.isBot(user)
                || UserObject.isReplyUser(user)
            ) {
                continue
            }

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

        if (!untilRevive && cachedFetcher.fetch(
                accountId,
                peer.id,
                day
            ) is ChatHistoryFetcher.Status.FromBoth
        )
            return Action.GROW

        return when (val status = remoteFetcher.fetch(accountId, peer.id, day, untilRevive)) {
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
        serviceMessagePolicy: ServiceMessagePolicy = ServiceMessagePolicy.REBUILD,
        onProgressUpdate: (progress: RebuildProgress) -> Unit,
    ) {
        if (!ignoreLock && !rebuildLock.compareAndSet(false, true)) {
            Plugin.getInstance()
                ?.log("Unable to rebuild peer $accountId:${peer.id} because another rebuild is already running")
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
                    peerLabel = getPeerLabel(peer),
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
                        // Скипаем текущий и предыдущий день, т.к. сегодня reviveNow, а вчера 100% сдох
                        currentDay = checkedDay.minusDays(2)
                    }
                }

                onProgressUpdate(progress)

                if (shouldStop)
                    break
            }

            val rebuildFrom = currentDay
            val rebuildTo = if (startDayIsFrozen) startDay.prev() else startDay

            if (
                serviceMessagePolicy.sendCreation
                || serviceMessagePolicy.sendUpgrade
                || serviceMessagePolicy.sendDeath
                || serviceMessagePolicy.sendRestore
            ) {
                Plugin.getInstance()?.log("Rebuild service messages policy is unexpectedly enabled")
            }

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
            Plugin.getInstance()?.logException("Failed to rebuild peer $accountId:${peer.id}", e)
        } finally {
            if (!ignoreLock)
                rebuildLock.set(false)
        }
    }

    suspend fun rebuildAll(
        accountId: Int,
        serviceMessagePolicy: ServiceMessagePolicy = ServiceMessagePolicy.REBUILD,
        onProgressUpdate: (index: Int, total: Int, peer: TLRPC.User, progress: RebuildProgress) -> Unit
    ): RebuildAllResult {
        if (!rebuildLock.compareAndSet(false, true)) {
            Plugin.getInstance()
                ?.log("Unable to rebuild all peers for $accountId because another rebuild is already running")
            return RebuildAllResult(0, emptyList())
        }

        try {
            val ownerUserId = UserConfig.getInstance(accountId).clientUserId
            val revives = reviveDao.findAllByOwnerUserId(ownerUserId).toSet()
            val peers = collectEligiblePrivateUsers(accountId)

            for ((index, user) in peers.withIndex()) {
                val peerRevives =
                    revives.filter { it.peerUserId == user.id }.map { it.revivedAt }.toSet()

                rebuild(accountId, user, peerRevives, true, serviceMessagePolicy) {
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
        serviceMessagePolicy: ServiceMessagePolicy = ServiceMessagePolicy.UPDATE_CHECK
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
                Action.GROW -> currentDay = currentDay.next()

                Action.REVIVE -> {
                    revives.add(currentDay)
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
                            currentDay.next(),
                            sendServiceMessage =
                                serviceMessagePolicy.sendDeath && isVisibleLength(streakBeforeDeath.length)
                        )
                        return
                    }

                    revives.add(currentDay.next())
                    // скипнуть текущий день смерти и следующий с reviveNow
                    currentDay = currentDay.plusDays(2)
                }
            }

            updateFromOwnerAt = currentDay
            updateFromPeerAt = currentDay
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
            serviceMessagePolicy.sendUpgrade
            && isVisibleLength(previousLength)
            && currentStreak.level.length > previousLevelLength
        ) {
            serviceMessagesController.sendUpgrade(accountId, peerUserId, currentStreak.length)
        }
    }

    suspend fun handleUpdate(
        accountId: Int,
        peerUserId: Long,
        out: Boolean,
        message: String?,
        serviceMessagePolicy: ServiceMessagePolicy = ServiceMessagePolicy.LIVE
    ): HandleUpdateResult {
        val now = LocalDate.now()
        val existingStreak = get(accountId, peerUserId)

        if (ServiceMessage.isServiceText(message)) {
            if (message == ServiceMessage.RESTORE_TEXT && existingStreak?.dead == true && existingStreak.canRevive) {
                reviveNow(accountId, peerUserId, false)
                return HandleUpdateResult(changed = true, created = false)
            }

            return HandleUpdateResult(changed = false, created = false)
        }

        val streak: Streak = existingStreak ?: run {
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

            if (serviceMessagePolicy.sendCreation && isVisibleLength(streak.length))
                serviceMessagesController.sendCreation(accountId, peerUserId)

            return HandleUpdateResult(
                changed = true,
                created = isVisibleLength(streak.length),
            )
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

        val currentStreak = get(accountId, peerUserId)
            ?: return HandleUpdateResult(changed = false, created = false)

        streakPopupController.enqueueForTransition(accountId, peerUserId, streak, currentStreak)

        if (
            serviceMessagePolicy.sendCreation
            && !isVisibleLength(streak.length)
            && isVisibleLength(currentStreak.length)
        ) {
            serviceMessagesController.sendCreation(accountId, peerUserId)
        } else if (
            serviceMessagePolicy.sendUpgrade
            && isVisibleLength(streak.length)
            && currentStreak.level.length > streak.level.length
        ) {
            serviceMessagesController.sendUpgrade(accountId, peerUserId, currentStreak.length)
        }

        return HandleUpdateResult(
            changed = true,
            created = wasCreated && isVisibleLength(currentStreak.length),
        )
    }

    suspend fun checkAllForUpdates(): List<UiSyncTarget> {
        val uiSyncTargets = mutableListOf<UiSyncTarget>()

        for (accountId in userConfigAuthorizedIds) {
            val streaks =
                dao.findAllByOwnerUserId(UserConfig.getInstance(accountId).clientUserId)

            streaks.forEach {
                checkForUpdates(accountId, it, ServiceMessagePolicy.UPDATE_CHECK)
                uiSyncTargets.add(UiSyncTarget(accountId, it.peerUserId))
            }
        }

        return uiSyncTargets
    }

    fun toggleServiceMessages(accountId: Int, peerUserId: Long): Boolean =
        serviceMessagesController.toggle(accountId, peerUserId)

    suspend fun flushCurrentChatPopup() =
        streakPopupController.flushCurrentChat()

    suspend fun get(accountId: Int, peerUserId: Long): Streak? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)

    suspend fun getAlive(accountId: Int, peerUserId: Long): Streak? =
        dao.findByRelation(UserConfig.getInstance(accountId).clientUserId, peerUserId)
            ?.let { if (it.dead || !isVisibleLength(it.length)) null else it }

    suspend fun getViewData(accountId: Int, peerUserId: Long): StreakViewData? {
        val streak = getAlive(accountId, peerUserId) ?: return null
        val streakLevel = streak.level

        return StreakViewData(
            streak.length,
            streakLevel.documentId,
            streakLevel.color,
            streak.length == streakLevel.length || streak.length % 100 == 0
        )
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
            ?.streakLevelRegistry
            ?.levels()
            ?.firstOrNull { it.length > streak.level.length }
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
        deathDate: LocalDate = LocalDate.now(),
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

        user.premium = true

        @Suppress("CAST_NEVER_SUCCEEDS")
        user.emoji_status = TLRPC.TL_emojiStatus()
            .apply { document_id = streakViewData.documentId } as TLRPC.EmojiStatus
    }

    suspend fun patchUsers(accountId: Int) {
        val streaks = dao.findAllByOwnerUserId(UserConfig.getInstance(accountId).clientUserId)

        for (streak in streaks) {
            val messagesController = MessagesController.getInstance(accountId)
            val user = messagesController.getUser(streak.peerUserId) ?: continue

            patchUser(accountId, user)

            messagesController.putUser(user, false, true)
        }
    }

    private suspend fun restoreUser(accountId: Int, userId: Long) {
        val messagesController = MessagesController.getInstance(accountId)

        val req = TLRPC.TL_users_getUsers().apply {
            id.add(userId)
        }

        val connectionsManager = ConnectionsManager.getInstance(accountId)

        val deferred = CompletableDeferred<MyResult<TLObject, TLRPC.TL_error>>()

        connectionsManager.sendRequest(req) { response, error ->
            deferred.complete(
                when {
                    error == null -> MyResult.Ok(response)
                    else -> MyResult.Err(error)
                }
            )
        }

        when (val result = deferred.await()) {
            is MyResult.Ok -> {
                @Suppress("CAST_NEVER_SUCCEEDS")
                val user = ((result as TLRPC.Users).users.getOrNull(0) ?: return) as TLRPC.User

                messagesController.putUser(user, false, true)
            }

            else -> return
        }
    }
}
