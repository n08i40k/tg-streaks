package ru.n08i40k.guides.model

internal fun validateGuideMessages(messages: List<GuideMessage>) {
    require(messages.map { it.id }.toSet().size == messages.size) { "Duplicate guide message IDs" }
    require(messages.zipWithNext().all { (a, b) -> a.date <= b.date }) {
        "Guide messages must be ordered oldest first"
    }
}
