package ru.n08i40k.guides.model

import ru.n08i40k.guides.api.GuideBadge

data class GuideChat(
    val id: Long,
    val name: String,
    val message: String,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val profile: GuideProfile? = null,
    val outgoing: Boolean = false,
    val badge: GuideBadge? = null,
) {
    init {
        require(unreadCount >= 0) { "Unread count cannot be negative" }
    }
}
