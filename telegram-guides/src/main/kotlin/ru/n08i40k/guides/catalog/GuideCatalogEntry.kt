package ru.n08i40k.guides.catalog

import ru.n08i40k.guides.model.GuideScript

class GuideCatalogEntry(
    val id: String,
    val title: String,
    val description: String,
    val create: () -> GuideScript,
)
