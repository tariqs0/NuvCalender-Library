package com.nuvio.app.features.calendar

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarHomeAndNavigationTest {
    private val today = CalendarDay.of(2026, 10, 8)

    @Test
    fun hidingCalendarFromNavigationKeepsTheHomeRow() {
        val hidden = CalendarSettingsUiState().withShowInNavigation(false)
        assertFalse(hidden.showInNavigation)
        assertTrue(hidden.showLibraryRowOnHome)

        // Removing the Home row while hidden restores the navigation entry.
        val rowOff = hidden.withLibraryRowOnHome(false)
        assertFalse(rowOff.showLibraryRowOnHome)
        assertTrue(rowOff.showInNavigation)

        // Both visible is allowed.
        val both = CalendarSettingsUiState().withLibraryRowOnHome(true)
        assertTrue(both.showLibraryRowOnHome && both.showInNavigation)
    }

    @Test
    fun homeAndNavigationSettingsPersist() {
        val state = CalendarSettingsUiState().withShowInNavigation(false)
        assertEquals(state, decodeCalendarSettings(encodeCalendarSettings(state)))
        // A stored "hidden" without the row (from an older build) still decodes as reachable.
        val legacy = decodeCalendarSettings("""{"show_in_navigation":false,"show_library_row_on_home":false}""")
        assertTrue(legacy.showLibraryRowOnHome)
        // Defaults: Calendar in navigation, no Home row.
        val defaults = decodeCalendarSettings(null)
        assertTrue(defaults.showInNavigation)
        assertFalse(defaults.showLibraryRowOnHome)
    }

    @Test
    fun homeRowShowsEachTitlesNextReleaseWithinThirtyDays() {
        fun episode(id: String, offset: Int, number: Int) = CalendarEntry(
            day = today.plusDays(offset),
            preview = MetaPreview(id = id, type = "series", name = id),
            kind = CalendarEntryKind.Episode,
            episodes = listOf(CalendarEpisode(season = 1, episode = number, videoId = "$id:1:$number", title = null)),
        )
        val entries = listOf(
            episode("weekly", 1, 2), episode("weekly", 8, 3), episode("weekly", 15, 4),
            episode("today", 0, 5),
            episode("past", -1, 1),
            episode("far", 45, 1),
        )
        val feed = CalendarFeedState(entriesByDay = entries.groupBy { it.day.epochDay })
        val row = nextLibraryReleases(feed, today)
        assertEquals(listOf("today", "weekly"), row.map { it.preview.id })
        assertEquals(2, row.last().episode)
    }

    @Test
    fun libraryServiceFilterUsesTheSameRefinementAsGlobal() {
        val onNetflix = CalendarEntry(
            day = today,
            preview = MetaPreview(id = "a", type = "series", name = "A"),
            kind = CalendarEntryKind.Episode,
            facets = CalendarFacets(services = setOf(CalendarStreamingService.Netflix)),
        )
        val elsewhere = onNetflix.copy(preview = onNetflix.preview.copy(id = "b", name = "B"), facets = CalendarFacets())
        val feed = CalendarFeedState(entriesByDay = mapOf(today.epochDay to listOf(onNetflix, elsewhere)))
        val netflixOnly = feed.filtered(CalendarContentFilter.All, CalendarRefinement(services = setOf("Netflix")))
        assertEquals(listOf("A"), netflixOnly.entriesFor(today).map { it.preview.name })
        val others = feed.filtered(CalendarContentFilter.All, CalendarRefinement(services = setOf(CalendarStreamingService.OTHERS)))
        assertEquals(listOf("B"), others.entriesFor(today).map { it.preview.name })
    }
}
