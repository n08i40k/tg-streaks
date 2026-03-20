package ru.n08i40k.streaks.resource

import java.io.File

class ResourcesProvider(
    resourcesRootPath: String?,
) {
    private val resourcesRoot =
        resourcesRootPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)

    fun resolvePopupResource(resourceName: String?): File? {
        return resourcesRoot
            ?.resolve("upgrade-popups")
            ?.resolve(resourceName?.trim().orEmpty())
            ?.takeIf { it.isFile }
    }
}
