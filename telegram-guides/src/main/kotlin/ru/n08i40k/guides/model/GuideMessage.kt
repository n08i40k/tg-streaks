package ru.n08i40k.guides.model

import ru.n08i40k.guides.api.GuideMessageContent

data class GuideMessage(
    val id: Int,
    val text: String,
    val outgoing: Boolean,
    val date: Int,
    val content: GuideMessageContent? = null,
) {
    init {
        require(id > 0) { "Guide message IDs must be positive" }
        require(date > 0) { "Guide message dates must be Unix seconds" }
    }
}
