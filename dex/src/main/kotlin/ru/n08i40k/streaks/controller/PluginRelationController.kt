package ru.n08i40k.streaks.controller

import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.data.PluginRelation
import ru.n08i40k.streaks.database.PluginDatabase

class PluginRelationController(
    db: PluginDatabase,
    private val serviceMessageCategoriesController: ServiceMessageCategoriesController
) {
    private val dao = db.pluginRelationDao()

    suspend fun hasPlugin(ownerUserId: Long, peerUserId: Long): Boolean =
        dao.findByRelation(ownerUserId, peerUserId)?.hasPlugin ?: false

    suspend fun setHasPlugin(ownerUserId: Long, peerUserId: Long, hasPlugin: Boolean) {
        dao.insertOrReplace(
            PluginRelation(
                ownerUserId = ownerUserId,
                peerUserId = peerUserId,
                hasPlugin = hasPlugin,
            )
        )

        serviceMessageCategoriesController.setEnabledBatch(
            ownerUserId,
            peerUserId,
            mapOf(
                ServiceMessageCategory.LIFECYCLE to hasPlugin,
                ServiceMessageCategory.LEVEL_UP to hasPlugin,
                ServiceMessageCategory.PET to hasPlugin,
            )
        )
    }
}
