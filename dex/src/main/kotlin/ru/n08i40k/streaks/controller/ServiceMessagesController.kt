package ru.n08i40k.streaks.controller

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.util.MessageSender

class ServiceMessagesController {
    companion object {
        private const val PREFERENCES_NAME = "tg-streaks-service-messages"

        private fun preferenceKey(accountId: Int, peerUserId: Long): String =
            "enabled:$accountId:$peerUserId"
    }

    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        ApplicationLoader.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    fun isEnabled(accountId: Int, peerUserId: Long): Boolean {
        if (accountId < 0 || peerUserId <= 0L)
            return false

        return preferences.getBoolean(preferenceKey(accountId, peerUserId), false)
    }

    fun toggle(accountId: Int, peerUserId: Long): Boolean {
        val enabled = !isEnabled(accountId, peerUserId)
        return setEnabled(accountId, peerUserId, enabled)
    }

    fun setEnabled(accountId: Int, peerUserId: Long, enabled: Boolean): Boolean {
        if (accountId < 0 || peerUserId <= 0L)
            return enabled

        preferences.edit()
            .putBoolean(preferenceKey(accountId, peerUserId), enabled)
            .apply()

        return enabled
    }

    fun sendCreation(accountId: Int, peerUserId: Long) {
        if (!isEnabled(accountId, peerUserId))
            return

        MessageSender.send(accountId, peerUserId, ServiceMessage.CREATE_TEXT)
    }

    fun sendPetInvite(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.PET_INVITE_TEXT)
    }

    fun sendPetInviteAccepted(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
    }

    fun sendPetSetName(accountId: Int, peerUserId: Long, name: String) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.PET_SET_NAME_TEXT(name))
    }

    fun sendUpgrade(accountId: Int, peerUserId: Long, length: Int) {
        if (!isEnabled(accountId, peerUserId))
            return

        MessageSender.send(accountId, peerUserId, ServiceMessage.UPGRADE_TEXT(length))
    }

    fun sendDeath(accountId: Int, peerUserId: Long) {
        if (!isEnabled(accountId, peerUserId))
            return

        MessageSender.send(accountId, peerUserId, ServiceMessage.DEATH_TEXT)
    }

    fun sendRestore(accountId: Int, peerUserId: Long) {
        if (!isEnabled(accountId, peerUserId))
            return

        MessageSender.send(accountId, peerUserId, ServiceMessage.RESTORE_TEXT)
    }
}
