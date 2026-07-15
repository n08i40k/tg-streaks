package ru.n08i40k.streaks.controller

import kotlinx.datetime.TimeZone
import ru.n08i40k.streaks.data.PeerTimeZone
import ru.n08i40k.streaks.database.PluginDatabase

class TimeZonesController(db: PluginDatabase) {
    private val dao = db.peerTimeZoneDao()

    suspend fun get(ownerUserId: Long, peerUserId: Long): TimeZone =
        dao.findByRelation(ownerUserId, peerUserId)?.timeZone
            ?: TimeZone.currentSystemDefault()

    suspend fun set(ownerUserId: Long, peerUserId: Long, timeZone: TimeZone) =
        dao.insertOrReplace(
            PeerTimeZone(
                ownerUserId = ownerUserId,
                peerUserId = peerUserId,
                timeZone = timeZone,
            )
        )
}
