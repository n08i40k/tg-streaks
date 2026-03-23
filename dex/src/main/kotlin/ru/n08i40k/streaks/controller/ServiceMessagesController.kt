@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.controller

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.SendMessagesHelper
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.util.isClientVersionBelow

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

        send(accountId, peerUserId, ServiceMessage.CREATE_TEXT)
    }

    fun sendPetInvite(accountId: Int, peerUserId: Long) {
        send(accountId, peerUserId, ServiceMessage.PET_INVITE_TEXT)
    }

    fun sendPetInviteAccepted(accountId: Int, peerUserId: Long) {
        send(accountId, peerUserId, ServiceMessage.PET_INVITE_ACCEPTED_TEXT)
    }

    fun sendPetSetName(accountId: Int, peerUserId: Long, name: String) {
        send(accountId, peerUserId, ServiceMessage.PET_SET_NAME_TEXT(name))
    }

    fun sendUpgrade(accountId: Int, peerUserId: Long, length: Int) {
        if (!isEnabled(accountId, peerUserId))
            return

        send(accountId, peerUserId, ServiceMessage.UPGRADE_TEXT(length))
    }

    fun sendDeath(accountId: Int, peerUserId: Long) {
        if (!isEnabled(accountId, peerUserId))
            return

        send(accountId, peerUserId, ServiceMessage.DEATH_TEXT)
    }

    fun sendRestore(accountId: Int, peerUserId: Long) {
        if (!isEnabled(accountId, peerUserId))
            return

        send(accountId, peerUserId, ServiceMessage.RESTORE_TEXT)
    }

    private fun send(accountId: Int, peerUserId: Long, message: String) {
        if (isClientVersionBelow("12.2.0")) {
            SendMessagesHelper::class.java.getDeclaredMethod(
                "prepareSendingText",
                AccountInstance::class.java,
                String::class.java,
                Long::class.java,
                Boolean::class.java,
                Int::class.java,
                Long::class.java
            ).invoke(
                null,
                AccountInstance.getInstance(accountId),
                message,
                peerUserId,
                false,
                0,
                0L
            )
        } else {
            SendMessagesHelper::class.java.getDeclaredMethod(
                "prepareSendingText",
                AccountInstance::class.java,
                String::class.java,
                Long::class.java,
                Boolean::class.java,
                Int::class.java,
                Int::class.java,
                Long::class.java
            ).invoke(
                null,
                AccountInstance.getInstance(accountId),
                message,
                peerUserId,
                false,
                0,
                0,
                0L
            )
        }
    }
}
