package ru.n08i40k.streaks.controller

import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.data.ServiceMessageCategories
import ru.n08i40k.streaks.database.PluginDatabase

class ServiceMessageCategoriesController(db: PluginDatabase) {
    private val dao = db.serviceMessageCategoriesDao()

    private fun defaultRecord(ownerUserId: Long, peerUserId: Long) = ServiceMessageCategories(
        ownerUserId = ownerUserId,
        peerUserId = peerUserId,
        lifecycle = true,
        levelUp = false,
        pet = false,
        sync = false,
    )

    private fun ServiceMessageCategories.value(category: String): Boolean = when (category) {
        ServiceMessageCategory.LIFECYCLE -> lifecycle
        ServiceMessageCategory.LEVEL_UP -> levelUp
        ServiceMessageCategory.PET -> pet
        else -> throw IllegalArgumentException("Unknown service message category: $category")
    }

    private fun ServiceMessageCategories.withValue(category: String, value: Boolean) =
        when (category) {
            ServiceMessageCategory.LIFECYCLE -> copy(lifecycle = value)
            ServiceMessageCategory.LEVEL_UP -> copy(levelUp = value)
            ServiceMessageCategory.PET -> copy(pet = value)
            else -> throw IllegalArgumentException("Unknown service message category: $category")
        }

    private fun ServiceMessageCategories.withValues(values: Map<String, Boolean>): ServiceMessageCategories {
        var result = this
        values.forEach { (category, value) -> result = result.withValue(category, value) }
        return result
    }

    private suspend fun getRecord(ownerUserId: Long, peerUserId: Long): ServiceMessageCategories =
        dao.findByRelation(ownerUserId, peerUserId)
            ?: defaultRecord(ownerUserId, peerUserId).also { dao.insertOrReplace(it) }

    suspend fun isEnabled(ownerUserId: Long, peerUserId: Long, category: String): Boolean =
        getRecord(ownerUserId, peerUserId).value(category)

    suspend fun setEnabled(ownerUserId: Long, peerUserId: Long, category: String, value: Boolean) {
        dao.insertOrReplace(getRecord(ownerUserId, peerUserId).withValue(category, value))
    }

    suspend fun setEnabledBatch(
        ownerUserId: Long,
        peerUserId: Long,
        updated: Map<String, Boolean>
    ) = dao.insertOrReplace(getRecord(ownerUserId, peerUserId).withValues(updated))
}
