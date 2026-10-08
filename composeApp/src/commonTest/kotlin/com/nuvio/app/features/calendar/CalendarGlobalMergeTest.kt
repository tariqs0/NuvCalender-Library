package com.nuvio.app.features.calendar

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarGlobalMergeTest {
    private val day = CalendarDay.of(2026, 10, 7)

    private fun show(id: String, name: String, imdb: String?, tmdb: Int?, year: Int? = 2003) =
        GlobalShow(MetaPreview(id = id, type = "series", name = name), imdb, tmdb, year)

    private fun episode(show: GlobalShow, season: Int, episode: Int, on: CalendarDay = day) =
        GlobalEpisode(show, on, season, episode, title = null, thumbnail = null, videoId = "${show.preview.id}:$season:$episode")

    private fun movie(id: String, name: String, imdb: String?, tmdb: Int?, kind: CalendarEntryKind = CalendarEntryKind.MovieRelease, on: CalendarDay = day) =
        GlobalTitle(MetaPreview(id = id, type = "movie", name = name), on, kind, imdb, tmdb, on.year, sourceLabel = null)

    @Test
    fun sameEpisodeFromCinemetaAndTmdbIsKeptOnceWithTheFirstSourcesId() {
        val cinemeta = show("tt0364845", "NCIS", imdb = "tt0364845", tmdb = 4614)
        val tmdb = show("tmdb:4614", "NCIS", imdb = null, tmdb = 4614)
        val merged = mergeGlobalCalendar(
            episodeSources = listOf(listOf(episode(cinemeta, 24, 1)), listOf(episode(tmdb, 24, 1), episode(tmdb, 24, 2, day.plusDays(7)))),
            titleSources = emptyList(),
        )
        val entries = merged.values.flatten()
        assertEquals(2, entries.size)
        assertEquals("tt0364845", merged.getValue(day.epochDay).single().preview.id)
        assertEquals(CalendarEntryKind.SeasonPremiere, merged.getValue(day.epochDay).single().kind)
    }

    @Test
    fun titleAndYearMatchCatchesSourcesWithoutSharedIds() {
        val a = show("tt1", "Hudson & Rex", imdb = "tt1", tmdb = null, year = 2019)
        val b = show("tmdb:2", "Hudson and Rex", imdb = null, tmdb = 2, year = 2019)
        val c = show("tmdb:3", "Hudson & Rex", imdb = null, tmdb = 3, year = 2019)
        val merged = mergeGlobalCalendar(listOf(listOf(episode(a, 9, 1)), listOf(episode(b, 9, 1), episode(c, 9, 1))), emptyList())
        // "Hudson and Rex" normalises differently, so it survives; the exact title is a duplicate.
        assertEquals(setOf("tt1", "tmdb:2"), merged.values.flatten().map { it.preview.id }.toSet())
    }

    @Test
    fun sameNamedShowsFromDifferentYearsAreNotMerged() {
        val old = show("tt0361200", "Line of Fire", imdb = "tt0361200", tmdb = null, year = 2003)
        val new = show("tt39365612", "Line of Fire", imdb = "tt39365612", tmdb = null, year = 2026)
        val merged = mergeGlobalCalendar(listOf(listOf(episode(old, 1, 3), episode(new, 1, 3))), emptyList())
        assertEquals(2, merged.values.flatten().size)
    }

    @Test
    fun bingeDropsGroupIntoOneEntryAfterPerEpisodeDedupe() {
        val s = show("tt9", "Show", imdb = "tt9", tmdb = 9)
        val merged = mergeGlobalCalendar(
            listOf((1..4).map { episode(s, 2, it) }, (3..8).map { episode(s, 2, it) }),
            emptyList(),
        )
        val entry = merged.getValue(day.epochDay).single()
        assertEquals((1..8).toList(), entry.episodes.map { it.episode })
    }

    @Test
    fun moviesDedupeByTmdbIdAcrossCinemetaAndTmdbButKeepDigitalReleases() {
        val merged = mergeGlobalCalendar(
            episodeSources = emptyList(),
            titleSources = listOf(
                listOf(movie("tt100", "Verity", imdb = "tt100", tmdb = 55)),
                listOf(
                    movie("tmdb:55", "Verity", imdb = null, tmdb = 55, on = day.plusDays(1)),
                    movie("tmdb:55", "Verity", imdb = null, tmdb = 55, kind = CalendarEntryKind.MovieDigitalRelease, on = day.plusDays(30)),
                ),
            ),
        )
        val entries = merged.values.flatten()
        assertEquals(2, entries.size)
        assertEquals("tt100", entries.single { it.kind == CalendarEntryKind.MovieRelease }.preview.id)
    }

    @Test
    fun catalogSeriesPremiereYieldsToARealPilotEpisode() {
        val s = show("tt5", "New Show", imdb = "tt5", tmdb = null, year = 2026)
        val catalogPremiere = GlobalTitle(
            MetaPreview(id = "tt5", type = "series", name = "New Show"),
            day, CalendarEntryKind.SeriesPremiere, "tt5", null, 2026, sourceLabel = "Cinemeta",
        )
        val merged = mergeGlobalCalendar(listOf(listOf(episode(s, 1, 1))), listOf(listOf(catalogPremiere)))
        val entry = merged.values.flatten().single()
        assertTrue(entry.isEpisodic)
        assertEquals(CalendarEntryKind.SeriesPremiere, entry.kind)
    }

    @Test
    fun contentFilterSplitsMoviesFromSeries() {
        val s = show("tt9", "Show", imdb = "tt9", tmdb = 9)
        val feed = CalendarFeedState(
            entriesByDay = mergeGlobalCalendar(
                listOf(listOf(episode(s, 1, 2))),
                listOf(listOf(movie("tt1", "Film", "tt1", null))),
            ),
        )
        assertEquals(2, feed.filtered(CalendarContentFilter.All).entriesFor(day).size)
        assertEquals(listOf("Film"), feed.filtered(CalendarContentFilter.Movies).entriesFor(day).map { it.preview.name })
        assertEquals(listOf("Show"), feed.filtered(CalendarContentFilter.Series).entriesFor(day).map { it.preview.name })
    }

    @Test
    fun monthPostersFillTheCellHeightAndShrinkInNarrowCells() {
        val desktop = monthPosterSize(maxWidth = 150.dp, maxHeight = 90.dp)
        assertEquals(90.dp, desktop.height)
        assertEquals(60.dp, desktop.width)

        val phone = monthPosterSize(maxWidth = 20.dp, maxHeight = 40.dp)
        assertEquals(20.dp, phone.width)
        assertEquals(30.dp, phone.height)
    }

    @Test
    fun facetsNormaliseGenresCountriesAndDetectAnime() {
        val tmdbShow = CalendarFacets.of(tmdbGenreNames(listOf(10759, 16, 10765)), language = "ja", countries = listOf("JP"))
        assertEquals(setOf("Action", "Adventure", "Animation", "Sci-Fi", "Fantasy"), tmdbShow.genres)
        assertTrue(tmdbShow.isAnime)

        // Cinemeta: country names, no language; language comes from the first country.
        val cinemeta = CalendarFacets.of(listOf("Animation", "Action"), language = null, countries = "Japan, United States".split(','))
        assertEquals(setOf("JP", "US"), cinemeta.countries)
        assertEquals("ja", cinemeta.language)
        assertTrue(cinemeta.isAnime)

        val western = CalendarFacets.of(listOf("Animation", "Comedy"), countries = listOf("United States"))
        assertEquals("en", western.language)
        assertTrue(!western.isAnime)
        assertEquals("United States", countryDisplayName("US"))
        assertEquals("Japanese", languageDisplayName("ja"))
    }

    @Test
    fun animeIsItsOwnCategoryAndRefinementsNarrowTheFeed() {
        val anime = show("tt20", "One Piece", "tt20", 37854, 1999).copy(
            facets = CalendarFacets.of(listOf("Animation", "Action"), "ja", listOf("JP")),
        )
        val drama = show("tt21", "NCIS", "tt21", 4614).copy(
            facets = CalendarFacets.of(listOf("Crime", "Drama"), "en", listOf("US")),
        )
        val feed = CalendarFeedState(
            entriesByDay = mergeGlobalCalendar(listOf(listOf(episode(anime, 22, 5), episode(drama, 24, 2))), emptyList()),
        )
        fun names(filter: CalendarContentFilter, refinement: CalendarRefinement = CalendarRefinement()) =
            feed.filtered(filter, refinement).entriesFor(day).map { it.preview.name }.toSet()

        assertEquals(setOf("One Piece"), names(CalendarContentFilter.Anime))
        assertEquals(setOf("NCIS"), names(CalendarContentFilter.Series))
        assertEquals(setOf("NCIS"), names(CalendarContentFilter.All, CalendarRefinement(genres = setOf("Crime"))))
        assertEquals(setOf("One Piece"), names(CalendarContentFilter.All, CalendarRefinement(languages = setOf("ja"))))
        assertEquals(setOf("NCIS"), names(CalendarContentFilter.All, CalendarRefinement(countries = setOf("US"))))

        val options = CalendarFilterOptions.from(feed)
        assertTrue(options.languages.map { it.first }.containsAll(listOf("en", "ja")))
    }

    @Test
    fun sortOrdersByTitleOrRating() {
        fun titled(name: String, rating: String?, popularity: Double) = CalendarEntry(
            day = day,
            preview = MetaPreview(id = name, type = "movie", name = name, imdbRating = rating, popularity = popularity),
            kind = CalendarEntryKind.MovieRelease,
        )
        val entries = listOf(titled("Beta", "6.1", 90.0), titled("Alpha", "8.4", 10.0), titled("Gamma", null, 50.0))
        assertEquals(listOf("Beta", "Gamma", "Alpha"), entries.sortedForDisplay(CalendarSort.All).map { it.preview.name })
        assertEquals(listOf("Alpha", "Beta", "Gamma"), entries.sortedForDisplay(CalendarSort.Title).map { it.preview.name })
        assertEquals(listOf("Alpha", "Beta", "Gamma"), entries.sortedForDisplay(CalendarSort.Rating).map { it.preview.name })
    }

    @Test
    fun streamingServicesTagEntriesAndOthersCoversTheRest() {
        val netflixShow = show("tt30", "Lupin", "tt30", 96677, 2021)
        val networkShow = show("tt31", "NCIS", "tt31", 4614)
        val merged = mergeGlobalCalendar(
            episodeSources = listOf(listOf(episode(netflixShow, 4, 1), episode(networkShow, 24, 2))),
            titleSources = listOf(listOf(movie("tmdb:7", "Doing Life", imdb = null, tmdb = 7))),
            servicesFor = { isMovie, tmdbId ->
                when {
                    !isMovie && tmdbId == 96677 -> setOf(CalendarStreamingService.Netflix)
                    isMovie && tmdbId == 7 -> setOf(CalendarStreamingService.Netflix, CalendarStreamingService.PrimeVideo)
                    // TV id 7 is a different title than movie id 7.
                    else -> emptySet()
                }
            },
        )
        val feed = CalendarFeedState(merged)
        fun names(vararg services: String) =
            feed.filtered(CalendarContentFilter.All, CalendarRefinement(services = services.toSet()))
                .entriesFor(day).map { it.preview.name }.toSet()

        assertEquals(setOf("Lupin", "Doing Life"), names(CalendarStreamingService.Netflix.name))
        assertEquals(setOf("Doing Life"), names(CalendarStreamingService.PrimeVideo.name))
        assertEquals(setOf("NCIS"), names(CalendarStreamingService.OTHERS))
        assertEquals(setOf("Lupin", "Doing Life", "NCIS"), names(CalendarStreamingService.Netflix.name, CalendarStreamingService.OTHERS))

        val options = CalendarFilterOptions.from(feed)
        assertEquals(2, options.services.first { it.first == CalendarStreamingService.Netflix }.second)
        assertEquals(1, options.othersCount)
    }

    @Test
    fun newSortPutsBrandNewTitlesFirst() {
        fun entry(name: String, kind: CalendarEntryKind, started: String, popularity: Double) = CalendarEntry(
            day = day,
            preview = MetaPreview(id = name, type = "series", name = name, releaseInfo = started, popularity = popularity),
            kind = kind,
        )
        val entries = listOf(
            entry("Old Hit", CalendarEntryKind.Episode, "2003-", 900.0),
            entry("New Show", CalendarEntryKind.SeriesPremiere, "2026-", 5.0),
            entry("Returning", CalendarEntryKind.SeasonPremiere, "2019-", 50.0),
            entry("Recent", CalendarEntryKind.Episode, "2025-", 10.0),
        )
        assertEquals(
            listOf("New Show", "Returning", "Recent", "Old Hit"),
            entries.sortedForDisplay(CalendarSort.New).map { it.preview.name },
        )
        // The stored name of the old default order still loads as All.
        assertEquals(CalendarSort.All, decodeCalendarSettings("""{"sort":"Popularity"}""").sort)
    }

    @Test
    fun cinemetaBatchSurvivesNullEntriesForUnknownIds() {
        val body = """
            {"metasDetailed":[
              {"id":"tt0364845","imdb_id":"tt0364845","name":"NCIS","year":"2003–","country":"United States",
               "genre":["Action","Crime"],"moviedb_id":4614,
               "videos":[{"id":"tt0364845:24:1","season":24,"episode":1,"released":"2026-10-07T00:00:00.000Z","name":"Ep"},null]},
              null,
              {"id":"tt7235466","name":"9-1-1","year":"2018–",
               "videos":[{"id":"tt7235466:10:1","season":10,"episode":1,"released":"2026-10-16T00:00:00.000Z"}]}
            ]}
        """.trimIndent()
        val shows = CinemetaCalendarSource.parseCalendarVideos(body)
        assertEquals(listOf("NCIS", "9-1-1"), shows.map { it.show.preview.name })
        assertEquals(4614, shows.first().show.tmdbId)
        assertEquals("2026-10-07", shows.first().episodes.single().day.toIsoString())
    }

    @Test
    fun newSettingsRoundTrip() {
        val state = CalendarSettingsUiState(
            contentFilter = CalendarContentFilter.Anime,
            monthDisplay = CalendarMonthDisplay.Posters,
            sort = CalendarSort.Rating,
            refinement = CalendarRefinement(genres = setOf("Drama"), languages = setOf("ko"), countries = setOf("KR")),
        )
        assertEquals(state, decodeCalendarSettings(encodeCalendarSettings(state)))
        assertEquals(CalendarMonthDisplay.Titles, decodeCalendarSettings("""{"view_mode":"Week"}""").monthDisplay)
    }
}
