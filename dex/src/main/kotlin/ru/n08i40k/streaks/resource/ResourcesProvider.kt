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

    fun resolveResource(relativePath: String?): File? {
        return resourcesRoot
            ?.resolve(relativePath?.trim().orEmpty())
            ?.takeIf { it.isFile }
    }

    fun readTextResource(relativePath: String?): String? {
        return resolveResource(relativePath)?.readText()
    }

    fun resolvePopupResource(resourceName: String?): File? {
        return resolveResource("upgrade-popups/${resourceName?.trim().orEmpty()}")
    }
}
