package ru.n08i40k.streaks.guide.lessons

import ru.n08i40k.guides.catalog.GuideCatalogEntry
import ru.n08i40k.guides.ui.GuideLabels
import ru.n08i40k.streaks.i18n.Strings

internal object StreakBasicsGuides {
    fun labels() =
        GuideLabels(
            catalogTitle = Strings.guide_catalog_title(),
            locked = Strings.guide_locked(),
            next = Strings.guide_next(),
            done = Strings.guide_done(),
            loadingTitle = Strings.guide_loading_title(),
            loadingDescription = Strings.guide_loading_description(),
            errorTitle = Strings.guide_error_title(),
            errorDescription = Strings.guide_error_description(),
            catalogDescription = Strings.guide_catalog_description(),
            chooseLesson = Strings.guide_catalog_choose(),
            startLesson = Strings.guide_catalog_start(),
            replayLesson = Strings.guide_catalog_replay(),
            close = Strings.guide_catalog_close(),
            copyLink = Strings.guide_catalog_copy_link(),
            progress = { done, total -> Strings.guide_catalog_progress(done, total) },
            profileError = { profile -> Strings.guide_error_profile(profile) },
        )

    fun catalog(): List<GuideCatalogEntry> =
        listOf(
            GuideCatalogEntry(
                "create",
                Strings.guide_create_title(),
                Strings.guide_create_description(),
                ::createStreakGuide
            ),
            GuideCatalogEntry(
                "maintain",
                Strings.guide_maintain_title(),
                Strings.guide_maintain_description(),
                ::maintainStreakGuide
            ),
            GuideCatalogEntry(
                "rebuild",
                Strings.guide_rebuild_title(),
                Strings.guide_rebuild_description(),
                ::rebuildStreakGuide
            ),
            GuideCatalogEntry(
                "restore",
                Strings.guide_restore_title(),
                Strings.guide_restore_description(),
                ::restoreStreakGuide
            ),
            GuideCatalogEntry(
                "calendar",
                Strings.guide_calendar_title(),
                Strings.guide_calendar_description(),
                ::calendarRestoreStreakGuide
            ),
            GuideCatalogEntry(
                "timezone",
                Strings.guide_timezone_title(),
                Strings.guide_timezone_description(),
                ::timeZoneStreakGuide
            ),
            GuideCatalogEntry(
                "sync",
                Strings.guide_sync_title(),
                Strings.guide_sync_description(),
                ::syncStreakGuide
            ),
            GuideCatalogEntry(
                "profile",
                Strings.guide_profile_title(),
                Strings.guide_profile_description(),
                ::showcaseStreakGuide
            ),
        )
}
