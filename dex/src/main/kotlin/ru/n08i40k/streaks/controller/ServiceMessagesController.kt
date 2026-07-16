package ru.n08i40k.streaks.controller

import android.net.Uri
import ru.n08i40k.streaks.constants.ServiceMessage
import ru.n08i40k.streaks.util.MessageSender

class ServiceMessagesController {
    fun sendCreation(accountId: Int, peerUserId: Long) {
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

    fun sendPetDeleted(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.PET_DELETED_TEXT)
    }

    fun sendUpgrade(accountId: Int, peerUserId: Long, length: Int) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.UPGRADE_TEXT(length))
    }

    fun sendDeath(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.DEATH_TEXT)
    }

    fun sendRestore(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.RESTORE_TEXT)
    }

    fun sendSyncOffer(accountId: Int, peerUserId: Long, db: Uri) {
        MessageSender.sendDocument(accountId, peerUserId, ServiceMessage.SYNC_OFFER, db)
    }

    fun sendSyncApplied(accountId: Int, peerUserId: Long) {
        MessageSender.send(accountId, peerUserId, ServiceMessage.SYNC_APPLIED)
    }
}
