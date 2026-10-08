package com.nuvio.app.features.library

import com.nuvio.app.features.calendar.CalendarFacets
import com.nuvio.app.features.calendar.CalendarRefinement
import com.nuvio.app.features.calendar.CalendarStreamingService
import com.nuvio.app.features.calendar.LibraryEpisodeRef
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.calendar.isLibraryRefreshDue
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryOrganizerTest {
    private val today = 20_000L

    private fun movie(id: String = "tt1", year: String? = "2024") =
        LibraryItem(id = id, type = "movie", name = id, releaseInfo = year, savedAtEpochMs = 1L)

    private fun series(id: String = "tt2", year: String? = "2019") =
        LibraryItem(id = id, type = "series", name = id, releaseInfo = year, savedAtEpochMs = 1L)

    private fun progress(
        parent: String,
        percent: Float,
        season: Int? = null,
        episode: Int? = null,
        updatedAt: Long = 100L,
    ) = WatchProgressEntry(
        contentType = if (season == null) "movie" else "series",
        parentMetaId = parent,
        parentMetaType = if (season == null) "movie" else "series",
        videoId = if (season == null) parent else "$parent:$season:$episode",
        title = parent,
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = 0L,
        durationMs = 7_200_000L,
        lastUpdatedEpochMs = updatedAt,
        progressPercent = percent,
    )

    private fun watchedEpisode(id: String, season: Int, episode: Int, at: Long = 50L) =
        WatchedItem(id = id, type = "series", name = id, season = season, episode = episode, markedAtEpochMs = at)

    private fun info(vararg aired: Triple<Int, Int, Long>) =
        LibraryTitleInfo(episodes = aired.map { (s, e, day) -> LibraryEpisodeRef(s, e, day) })

    @Test
    fun moviesMoveFromWatchlistToContinueWatchingToWatched() {
        val m = movie()
        assertEquals(LibrarySmartList.Watchlist, classifyLibraryItem(m, emptyList(), emptyList(), false, null, today).list)

        val partial = classifyLibraryItem(m, emptyList(), listOf(progress("tt1", 40f)), false, null, today)
        assertEquals(LibrarySmartList.ContinueWatching, partial.list)
        assertEquals(0.4f, partial.progressFraction)

        val finished = classifyLibraryItem(m, emptyList(), listOf(progress("tt1", 95f)), false, null, today)
        assertEquals(LibrarySmartList.Watched, finished.list)
        val marked = classifyLibraryItem(
            m, listOf(WatchedItem(id = "tt1", type = "movie", name = "tt1", markedAtEpochMs = 1L)), emptyList(), false, null, today,
        )
        assertEquals(LibrarySmartList.Watched, marked.list)
    }

    @Test
    fun fullyWatchedSeriesReturnsToContinueWatchingWhenANewEpisodeAirs() {
        val s = series()
        val aired = info(Triple(1, 1, today - 20), Triple(1, 2, today - 13))
        val watched = listOf(watchedEpisode("tt2", 1, 1), watchedEpisode("tt2", 1, 2, at = 80L))
        val caughtUp = classifyLibraryItem(s, watched, emptyList(), true, aired, today)
        assertEquals(LibrarySmartList.Watched, caughtUp.list)

        // Episode 3 airs today: the series moves back to Continue Watching with "next" set.
        val withNew = info(Triple(1, 1, today - 20), Triple(1, 2, today - 13), Triple(1, 3, today))
        val back = classifyLibraryItem(s, watched, emptyList(), true, withNew, today)
        assertEquals(LibrarySmartList.ContinueWatching, back.list)
        assertEquals(1 to 3, back.nextSeason to back.nextEpisode)
        assertEquals(1 to 2, back.lastSeason to back.lastEpisode)

        // An announced but not yet aired episode does not count.
        val future = info(Triple(1, 1, today - 20), Triple(1, 2, today - 13), Triple(1, 3, today + 7))
        assertEquals(LibrarySmartList.Watched, classifyLibraryItem(s, watched, emptyList(), false, future, today).list)
    }

    @Test
    fun seriesProgressShowsTheLastEpisodeAndPercent() {
        val s = series()
        val aired = info(Triple(2, 4, today - 30), Triple(2, 5, today - 23))
        val result = classifyLibraryItem(
            s,
            listOf(watchedEpisode("tt2", 2, 4, at = 10L)),
            listOf(progress("tt2", 45f, season = 2, episode = 5, updatedAt = 99L)),
            false,
            aired,
            today,
        )
        assertEquals(LibrarySmartList.ContinueWatching, result.list)
        assertEquals(2 to 5, result.lastSeason to result.lastEpisode)
        assertEquals(0.45f, result.progressFraction)

        // Never started → Watchlist.
        assertEquals(LibrarySmartList.Watchlist, classifyLibraryItem(s, emptyList(), emptyList(), false, aired, today).list)
        // A whole-series marker covers episodes aired before it was set.
        val marker = WatchedItem(id = "tt2", type = "series", name = "tt2", markedAtEpochMs = (today - 1) * 86_400_000L)
        assertEquals(LibrarySmartList.Watched, classifyLibraryItem(s, listOf(marker), emptyList(), false, aired, today).list)
    }

    @Test
    fun newSortsUseYearPopularityRatingAndLatestRelease() {
        val a = movie("a", "1999")
        val b = movie("b", "2024")
        val c = movie("c", null)
        val context = LibrarySortContext(
            titleInfo = mapOf(
                "movie:a" to LibraryTitleInfo(popularity = 5.0, rating = 8.9, releaseDays = listOf(today - 900)),
                "movie:b" to LibraryTitleInfo(popularity = 90.0, rating = 6.1, releaseDays = listOf(today - 3)),
                "movie:c" to LibraryTitleInfo(popularity = 40.0, startYear = 2010),
            ),
            todayEpochDay = today,
        )
        fun sorted(option: LibrarySortOption) =
            sortLibraryItems(listOf(a, b, c), option, LibrarySourceMode.LOCAL, context = context).map { it.id }
        assertEquals(listOf("b", "c", "a"), sorted(LibrarySortOption.YEAR_DESC))
        assertEquals(listOf("a", "c", "b"), sorted(LibrarySortOption.YEAR_ASC))
        assertEquals(listOf("b", "c", "a"), sorted(LibrarySortOption.TRENDING))
        assertEquals(listOf("a", "b", "c"), sorted(LibrarySortOption.RATING))
        assertEquals(listOf("b", "a", "c"), sorted(LibrarySortOption.NEW))
        assertEquals(
            listOf(LibrarySortOption.NEW, LibrarySortOption.TRENDING, LibrarySortOption.RATING),
            librarySortDialogOrder(availableLibrarySortOptions(LibrarySourceMode.LOCAL)).take(3),
        )
    }

    @Test
    fun filtersMatchServicesGenreDecadeCountryAndLanguage() {
        val netflixDrama = movie("n", "2021")
        val other = movie("o", "1995")
        val info = mapOf(
            "movie:n" to LibraryTitleInfo(
                facets = CalendarFacets(
                    genres = setOf("Drama"),
                    language = "ko",
                    countries = setOf("KR"),
                    services = setOf(CalendarStreamingService.Netflix),
                ),
            ),
            "movie:o" to LibraryTitleInfo(facets = CalendarFacets(genres = setOf("Comedy"), language = "en", countries = setOf("US"))),
        )
        fun names(refinement: LibraryRefinement) =
            listOf(netflixDrama, other).filter { refinement.matches(it, info[libraryDisplayItemKey(it)]) }.map { it.id }

        assertEquals(listOf("n"), names(LibraryRefinement(CalendarRefinement(services = setOf("Netflix")))))
        assertEquals(listOf("o"), names(LibraryRefinement(CalendarRefinement(services = setOf(CalendarStreamingService.OTHERS)))))
        assertEquals(listOf("o"), names(LibraryRefinement(CalendarRefinement(genres = setOf("Comedy")))))
        assertEquals(listOf("n"), names(LibraryRefinement(decades = setOf(2020))))
        assertEquals(listOf("n"), names(LibraryRefinement(CalendarRefinement(countries = setOf("KR")))))
        assertEquals(listOf("o"), names(LibraryRefinement(CalendarRefinement(languages = setOf("en")))))

        val options = LibraryFilterOptions.from(listOf(netflixDrama, other)) { info[libraryDisplayItemKey(it)] }
        assertEquals(listOf(2020 to 1, 1990 to 1), options.decades)
        assertEquals(1, options.othersCount)
    }

    @Test
    fun organizerHidesHiddenTitlesAndBuildsTheThreeLists() {
        val watching = movie("w")
        val todo = movie("t")
        val done = movie("d")
        val secret = movie("s")
        val section = LibrarySection(type = "movie", displayTitle = "Movies", items = listOf(watching, todo, done, secret))
        val hidden = LibraryHiddenUiState(
            pinEnabled = true,
            items = listOf(HiddenLibraryItem(id = "s", type = "movie", name = "s")),
        )
        val content = organizeLibrary(
            sections = listOf(section),
            hidden = hidden,
            selectedType = null,
            refinement = LibraryRefinement(),
            sortOption = LibrarySortOption.TITLE_ASC,
            sourceMode = LibrarySourceMode.LOCAL,
            titleInfo = emptyMap(),
            watchedItems = listOf(WatchedItem(id = "d", type = "movie", name = "d", markedAtEpochMs = 1L)),
            progressEntries = listOf(progress("w", 30f)),
            fullyWatchedSeriesKeys = emptySet(),
            todayEpochDay = today,
        )
        assertEquals(listOf("w"), content.smartLists.getValue(LibrarySmartList.ContinueWatching).map { it.id })
        assertEquals(listOf("t"), content.smartLists.getValue(LibrarySmartList.Watchlist).map { it.id })
        assertEquals(listOf("d"), content.smartLists.getValue(LibrarySmartList.Watched).map { it.id })
        assertFalse(content.visibleSections.single().items.any { it.id == "s" })
    }

    @Test
    fun hiddenPinMustBeFourDigitsConfirmedAndDifferentFromTheProfilePin() {
        assertNull(validateNewHiddenPin("2468", "2468", matchesProfilePin = false))
        assertEquals(HiddenPinError.InvalidFormat, validateNewHiddenPin("12a4", "12a4", false))
        assertEquals(HiddenPinError.InvalidFormat, validateNewHiddenPin("123", "123", false))
        assertEquals(HiddenPinError.Mismatch, validateNewHiddenPin("2468", "2469", false))
        assertEquals(HiddenPinError.SameAsProfilePin, validateNewHiddenPin("1234", "1234", matchesProfilePin = true))
        // Salted per profile: the same PIN never hashes the same twice.
        assertTrue(hashHiddenPin(1, "salt-a", "2468") != hashHiddenPin(1, "salt-b", "2468"))
        assertTrue(hashHiddenPin(1, "salt", "2468") != hashHiddenPin(2, "salt", "2468"))
        assertTrue(LibraryHiddenUiState(items = listOf(HiddenLibraryItem("x", "film", "x"))).isHidden("movie", "x"))
    }

    @Test
    fun libraryFiltersPersistWithTheDisplaySettings() {
        val state = LibraryDisplaySettingsUiState(
            sortOption = LibrarySortOption.YEAR_DESC,
            refinement = LibraryRefinement(
                calendar = CalendarRefinement(genres = setOf("Drama"), services = setOf("Netflix")),
                decades = setOf(1990, 2020),
            ),
        )
        assertEquals(state, decodeLibraryDisplaySettings(encodeLibraryDisplaySettings(state)))
        assertEquals(LibraryRefinement(), decodeLibraryDisplaySettings("""{"sort_option":"TITLE_ASC"}""").refinement)
    }

    @Test
    fun releaseDataIsReReadFromTheAddonsOnceStale() {
        val hour = 60 * 60 * 1000L
        assertFalse(isLibraryRefreshDue(lastRefreshEpochMs = null, nowEpochMs = 10 * hour), "nothing loaded yet")
        assertFalse(isLibraryRefreshDue(lastRefreshEpochMs = 10 * hour, nowEpochMs = 12 * hour))
        assertTrue(isLibraryRefreshDue(lastRefreshEpochMs = 10 * hour, nowEpochMs = 13 * hour))
    }

    @Test
    fun aNewlyAnnouncedSeasonMovesTheSeriesBackOnceItAirs() {
        // Data re-read from the addon now lists S2E1, airing tomorrow; the series stays Watched today
        // and returns to Continue Watching on the day it airs (the open Library rolls "today" over).
        val s = series()
        val watched = listOf(watchedEpisode("tt2", 1, 1), watchedEpisode("tt2", 1, 2))
        val refreshed = info(Triple(1, 1, today - 30), Triple(1, 2, today - 23), Triple(2, 1, today + 1))
        assertEquals(LibrarySmartList.Watched, classifyLibraryItem(s, watched, emptyList(), true, refreshed, today).list)
        val tomorrow = classifyLibraryItem(s, watched, emptyList(), true, refreshed, today + 1)
        assertEquals(LibrarySmartList.ContinueWatching, tomorrow.list)
        assertEquals(2 to 1, tomorrow.nextSeason to tomorrow.nextEpisode)
    }
}
