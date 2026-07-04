package ru.n08i40k.streaks.util

import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.userConfigAuthorizedIds
import java.util.LinkedHashMap

object UserPatcher : EjectNotifier.Delegate() {
    private const val FLAG_PATCHED: Int = 1 shl 32

    private data class OriginalUserState(val premium: Boolean)

    private val originalUserStates = LinkedHashMap<Long, OriginalUserState>()

    private fun applyUserState(user: TLRPC.User): Boolean {
        if (user.flags2 and FLAG_PATCHED != 0)
            return false

        originalUserStates[user.id] = OriginalUserState(premium = user.premium)

        user.flags2 = user.flags2 or FLAG_PATCHED
        user.premium = true

        return true
    }

    fun patchUser(accountId: Int, user: TLRPC.User) {
        val messagesController = MessagesController.getInstance(accountId)

        if (applyUserState(user))
            messagesController.putUser(user, false, true)
    }

    fun patchUser(accountId: Int, userId: Long) {
        val messagesController = MessagesController.getInstance(accountId)
        val user = messagesController.getUser(userId) ?: return

        if (applyUserState(user))
            messagesController.putUser(user, false, true)
    }

    fun patchUsers(accountId: Int, userIds: Collection<Long>) {
        val messagesController = MessagesController.getInstance(accountId)

        userIds.forEach { userId ->
            val user = messagesController.getUser(userId) ?: return

            if (applyUserState(user))
                messagesController.putUser(user, false, true)
        }
    }

    private fun restoreUser(accountId: Int, userId: Long) {
        val messagesController = MessagesController.getInstance(accountId)
        val originalState = originalUserStates.remove(userId) ?: return
        val user = messagesController.getUser(userId) ?: return

        user.premium = originalState.premium

        messagesController.putUser(user, false, true)
    }

    override fun onEject() {
        for (accountId in userConfigAuthorizedIds) {
            val messagesController = MessagesController.getInstance(accountId)

            for ((userId, originalState) in originalUserStates) {
                val user = messagesController.getUser(userId) ?: continue

                user.flags2 = user.flags2 and FLAG_PATCHED.inv()
                user.premium = originalState.premium

                messagesController.putUser(user, false, true)
            }
        }

        originalUserStates.clear()
    }

    fun TLRPC.User.isPatched(): Boolean = (flags2 and FLAG_PATCHED) != 0
}