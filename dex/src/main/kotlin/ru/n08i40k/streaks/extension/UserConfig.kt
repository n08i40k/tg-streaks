package ru.n08i40k.streaks.extension

import org.telegram.messenger.UserConfig

private object StaticData {
    val reverseIdMap = HashMap<Long, Int>(UserConfig.MAX_ACCOUNT_COUNT)
}

fun accountIdFromUserId(userId: Long): Int? = StaticData.reverseIdMap[userId]

val userConfigAuthorizedIds: Set<Int>
    get() {
        val shouldAdd = StaticData.reverseIdMap.isEmpty()
        val set = hashSetOf<Int>()

        for (id in 0..<UserConfig.MAX_ACCOUNT_COUNT) {
            val config = UserConfig.getInstance(id)

            if (!config.isClientActivated)
                continue

            if (shouldAdd)
                StaticData.reverseIdMap[config.clientUserId] = id

            set.add(id)
        }

        return set
    }

val userConfigAuthorizedUserIds: Set<Long>
    get() {
        val shouldAdd = StaticData.reverseIdMap.isEmpty()
        val set = hashSetOf<Long>()

        for (id in 0..<UserConfig.MAX_ACCOUNT_COUNT) {
            val config = UserConfig.getInstance(id)

            if (!config.isClientActivated)
                continue

            if (shouldAdd)
                StaticData.reverseIdMap[config.clientUserId] = id

            set.add(config.clientUserId)
        }

        return set
    }
