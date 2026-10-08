package com.nuvio.app.features.calendar

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarContentFilterTest {
    private val day = CalendarDay.of(2026, 10, 7)

    private fun entry(id: String, kind: CalendarEntryKind, facets: CalendarFacets = CalendarFacets()) =
        CalendarEntry(
            day = day,
            preview = MetaPreview(id = id, type = if (kind.isMovie) "movie" else "series", name = id),
            kind = kind,
            facets = facets,
        )

    private val movie = entry("movie", CalendarEntryKind.MovieRelease, CalendarFacets.of(listOf("Drama"), "en"))
    private val series = entry("series", CalendarEntryKind.Episode, CalendarFacets.of(listOf("Comedy"), "en"))
    private val anime = entry("anime", CalendarEntryKind.Episode, CalendarFacets.of(listOf("Action", "Anime")))
    private val japaneseAnimation = entry(
        "jp",
        CalendarEntryKind.MovieRelease,
        CalendarFacets.of(listOf("Animation"), countries = listOf("Japan")),
    )
    private val feed = CalendarFeedState(
        entriesByDay = mapOf(day.epochDay to listOf(movie, series, anime, japaneseAnimation)),
        hasLoaded = true,
    )

    private fun ids(filter: CalendarContentFilter, refinement: CalendarRefinement = CalendarRefinement()) =
        feed.filtered(filter, refinement).entriesFor(day).map { it.preview.id }

    @Test
    fun typeFilterSplitsMoviesSeriesAndAnime() {
        assertEquals(listOf("movie", "series", "anime", "jp"), ids(CalendarContentFilter.All))
        assertEquals(listOf("movie"), ids(CalendarContentFilter.Movies))
        assertEquals(listOf("series"), ids(CalendarContentFilter.Series))
        assertEquals(listOf("anime", "jp"), ids(CalendarContentFilter.Anime))
    }

    @Test
    fun refinementCombinesWithTypeFilter() {
        assertEquals(listOf("series"), ids(CalendarContentFilter.All, CalendarRefinement(genres = setOf("Comedy"))))
        assertEquals(emptyList(), ids(CalendarContentFilter.Movies, CalendarRefinement(genres = setOf("Comedy"))))
        assertEquals(listOf("jp"), ids(CalendarContentFilter.Anime, CalendarRefinement(languages = setOf("ja"))))
    }
}
