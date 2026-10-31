package ru.n08i40k.guides.model

sealed interface GuideProfile {
    data class UserId(val id: Long) : GuideProfile {
        init {
            require(id > 0) { "Telegram user IDs are positive" }
        }
    }

    data class Username(val username: String) : GuideProfile {
        val normalized: String = username.trim().removePrefix("@").lowercase()

        init {
            require(normalized.matches(Regex("[a-z0-9_]{1,32}"))) {
                "Use a Telegram username, optionally prefixed with @"
            }
        }
    }
}
