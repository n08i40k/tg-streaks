package ru.n08i40k.streaks.extension

import org.telegram.messenger.UserConfig

val userConfigAuthorizedIds: Set<Int>
    get() {
        val set = hashSetOf<Int>()

        for (id in 0..<UserConfig.MAX_ACCOUNT_COUNT) {
            val config = UserConfig.getInstance(id)

            if (!config.isClientActivated)
                continue

            set.add(id)
        }

        return set
    }