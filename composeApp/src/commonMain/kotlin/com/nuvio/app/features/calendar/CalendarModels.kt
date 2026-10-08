package com.nuvio.app.features.calendar

import com.nuvio.app.features.home.MetaPreview

enum class CalendarScope {
    Global,
    Personal,
}

enum class CalendarViewMode {
    Month,
    Week,
    Day,
}

enum class CalendarContentFilter {
    All,
    Movies,
    Series,
    Anime,
}

/** Order of releases within a day. */
enum class CalendarSort {
    /** Default: premieres first, then the most popular. */
    All,

    /** Brand-new titles first: premieres and new releases, then the most recently started. */
    New,
    Title,
    Rating,
}

/** Genre / language / country narrowing for the busy Global calendar; empty sets match all. */
internal data class CalendarRefinement(
    val genres: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    /** [CalendarStreamingService] names plus [CalendarStreamingService.OTHERS]. */
    val services: Set<String> = emptySet(),
) {
    val activeCount: Int get() = genres.size + languages.size + countries.size + services.size

    fun matches(facets: CalendarFacets): Boolean =
        (genres.isEmpty() || facets.genres.any(genres::contains)) &&
            (languages.isEmpty() || facets.language in languages) &&
            (countries.isEmpty() || facets.countries.any(countries::contains)) &&
            (services.isEmpty() || matchesService(facets.services))

    private fun matchesService(entryServices: Set<CalendarStreamingService>): Boolean =
        entryServices.any { it.name in services } ||
            (CalendarStreamingService.OTHERS in services && entryServices.isEmpty())
}

/** How much room the calendar gives each day and entry. */
enum class CalendarDensity {
    Compact,
    Comfortable,
    Spacious,
}

/** Weekdays a week may start on (ISO numbering: Monday = 1 … Sunday = 7). */
internal val CalendarWeekStartOptions = listOf(CalendarDay.MONDAY, CalendarDay.SUNDAY, CalendarDay.SATURDAY)

/** How the month grid draws entries: text chips (default) or poster thumbnails. */
enum class CalendarMonthDisplay {
    Titles,
    Posters,
}

enum class CalendarEntryKind {
    MovieRelease,
    MovieDigitalRelease,
    SeriesPremiere,
    SeasonPremiere,
    Episode,
}

internal data class CalendarEpisode(
    val season: Int?,
    val episode: Int?,
    val videoId: String,
    val title: String?,
)

internal data class CalendarEntry(
    val day: CalendarDay,
    val preview: MetaPreview,
    val kind: CalendarEntryKind,
    /** Episodes released on [day]; a binge drop collapses into one entry. Empty for titles. */
    val episodes: List<CalendarEpisode> = emptyList(),
    val thumbnail: String? = null,
    val sourceLabel: String? = null,
    val facets: CalendarFacets = CalendarFacets(),
) {
    val season: Int? get() = episodes.firstOrNull()?.season
    val episode: Int? get() = episodes.firstOrNull()?.episode
    val lastEpisode: Int? get() = episodes.takeIf { it.size > 1 }?.lastOrNull()?.episode
    val episodeTitle: String? get() = episodes.singleOrNull()?.title?.takeIf(String::isNotBlank)

    val key: String = "${day.epochDay}|${kind.name}|${preview.type}|${preview.id}|" +
        "${episodes.firstOrNull()?.season ?: ""}|${episodes.firstOrNull()?.episode ?: ""}"

    val isEpisodic: Boolean
        get() = episodes.isNotEmpty()
}

internal val CalendarEntryKind.isMovie: Boolean
    get() = this == CalendarEntryKind.MovieRelease || this == CalendarEntryKind.MovieDigitalRelease

/** Anime is its own category, so Movies and TV / Series exclude it. */
internal fun CalendarContentFilter.accepts(entry: CalendarEntry): Boolean = when (this) {
    CalendarContentFilter.All -> true
    CalendarContentFilter.Movies -> entry.kind.isMovie && !entry.facets.isAnime
    CalendarContentFilter.Series -> !entry.kind.isMovie && !entry.facets.isAnime
    CalendarContentFilter.Anime -> entry.facets.isAnime
}

internal data class CalendarFeedState(
    val entriesByDay: Map<Long, List<CalendarEntry>> = emptyMap(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    fun entriesFor(day: CalendarDay): List<CalendarEntry> = entriesByDay[day.epochDay].orEmpty()

    fun entriesIn(range: CalendarRange): List<CalendarEntry> =
        range.days.flatMap(::entriesFor)

    fun filtered(
        filter: CalendarContentFilter,
        refinement: CalendarRefinement = CalendarRefinement(),
    ): CalendarFeedState =
        if (filter == CalendarContentFilter.All && refinement.activeCount == 0) {
            this
        } else {
            copy(
                entriesByDay = entriesByDay
                    .mapValues { (_, entries) ->
                        entries.filter { filter.accepts(it) && refinement.matches(it.facets) }
                    }
                    .filterValues { it.isNotEmpty() },
            )
        }
}

/** The span of days each view mode shows around the focused date. */
internal fun CalendarViewMode.visibleRange(focus: CalendarDay, firstDayOfWeek: Int): CalendarRange =
    when (this) {
        CalendarViewMode.Month -> {
            val gridStart = focus.startOfMonth().startOfWeek(firstDayOfWeek)
            CalendarRange(gridStart, gridStart.plusDays(MonthGridDayCount - 1))
        }
        CalendarViewMode.Week -> {
            val weekStart = focus.startOfWeek(firstDayOfWeek)
            CalendarRange(weekStart, weekStart.plusDays(6))
        }
        CalendarViewMode.Day -> CalendarRange(focus, focus)
    }

internal fun CalendarViewMode.step(focus: CalendarDay, direction: Int): CalendarDay =
    when (this) {
        CalendarViewMode.Month -> focus.plusMonths(direction)
        CalendarViewMode.Week -> focus.plusDays(7 * direction)
        CalendarViewMode.Day -> focus.plusDays(direction)
    }

/** Six full weeks always fit any month regardless of the weekday it starts on. */
internal const val MonthGridDayCount = 42

internal fun List<CalendarEntry>.sortedForDisplay(sort: CalendarSort = CalendarSort.All): List<CalendarEntry> =
    sortedWith(
        when (sort) {
            CalendarSort.All -> compareBy<CalendarEntry> { it.kind.displayPriority }
                .thenByDescending { it.preview.popularity ?: 0.0 }
            CalendarSort.New -> compareBy<CalendarEntry> { if (it.isNewRelease) 0 else 1 }
                .thenByDescending { it.startYear ?: 0 }
                .thenByDescending { it.preview.popularity ?: 0.0 }
            CalendarSort.Title -> compareBy<CalendarEntry> { it.preview.name.lowercase() }
            CalendarSort.Rating -> compareByDescending<CalendarEntry> { it.preview.imdbRating?.toDoubleOrNull() ?: -1.0 }
                .thenByDescending { it.preview.popularity ?: 0.0 }
        }
            .thenBy { it.preview.name.lowercase() }
            .thenBy { it.season ?: 0 }
            .thenBy { it.episode ?: 0 },
    )

/** Premieres, new seasons and movie releases, as opposed to another weekly episode. */
internal val CalendarEntry.isNewRelease: Boolean
    get() = kind != CalendarEntryKind.Episode

/** Year the title (or the show) first came out. */
internal val CalendarEntry.startYear: Int?
    get() = leadingYear(preview.releaseInfo ?: preview.rawReleaseDate)

private val CalendarEntryKind.displayPriority: Int
    get() = when (this) {
        CalendarEntryKind.SeriesPremiere -> 0
        CalendarEntryKind.SeasonPremiere -> 1
        CalendarEntryKind.MovieRelease -> 2
        CalendarEntryKind.MovieDigitalRelease -> 3
        CalendarEntryKind.Episode -> 4
    }
