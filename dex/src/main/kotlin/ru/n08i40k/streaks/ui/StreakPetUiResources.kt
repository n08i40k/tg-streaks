package ru.n08i40k.streaks.ui

import ru.n08i40k.streaks.resource.ResourcesProvider

internal object StreakPetUiResources {
    private const val SHEET_HTML_PATH = "streak-pet/sheet.html"
    private const val FAB_HTML_PATH = "streak-pet/fab.html"
    private const val DEFAULT_BASE_URL = "https://tg-streaks.local/streak-pet/"

    private const val FALLBACK_SHEET_HTML =
        "<!DOCTYPE html><html><body style=\"margin:0;background:#fff\"></body></html>"

    private const val FALLBACK_FAB_HTML =
        "<!DOCTYPE html><html><body style=\"margin:0;background:transparent\"></body></html>"

    fun loadSheetHtml(resourcesProvider: ResourcesProvider, safeTopPx: Int): String {
        return resourcesProvider.readTextResource(SHEET_HTML_PATH)
            ?.replace("{{SAFE_TOP}}", safeTopPx.toString())
            ?: FALLBACK_SHEET_HTML
    }

    fun loadSheetBaseUrl(resourcesProvider: ResourcesProvider): String {
        return resourcesProvider.resolveResource(SHEET_HTML_PATH)
            ?.parentFile
            ?.toURI()
            ?.toString()
            ?: DEFAULT_BASE_URL
    }

    fun loadFabHtml(resourcesProvider: ResourcesProvider): String {
        return resourcesProvider.readTextResource(FAB_HTML_PATH) ?: FALLBACK_FAB_HTML
    }

    fun loadFabBaseUrl(resourcesProvider: ResourcesProvider): String {
        return resourcesProvider.resolveResource(FAB_HTML_PATH)
            ?.parentFile
            ?.toURI()
            ?.toString()
            ?: DEFAULT_BASE_URL
    }
}
