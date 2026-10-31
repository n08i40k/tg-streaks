package ru.n08i40k.guides.model

data class GuideStep(
    val heading: String,
    val description: String,
    val actions: List<GuideAction>,
)
