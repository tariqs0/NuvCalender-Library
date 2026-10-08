package com.nuvio.app.features.calendar

import com.nuvio.app.features.details.MetaDetails

/** One episode of a Library series and the day it airs (null when not dated yet). */
internal data class LibraryEpisodeRef(
    val season: Int,
    val episode: Int,
    val airEpochDay: Long?,
)

/**
 * What the Library page knows about a saved title beyond the Library item itself, gathered by
 * the Library calendar sync from the existing addons and integrations.
 */
internal data class LibraryTitleInfo(
    val facets: CalendarFacets = CalendarFacets(),
    val popularity: Double? = null,
    val rating: Double? = null,
    val startYear: Int? = null,
    /** All episodes of a series, dated where the addon provides an air date. */
    val episodes: List<LibraryEpisodeRef> = emptyList(),
    /** Days with a release (episode or movie), ascending. */
    val releaseDays: List<Long> = emptyList(),
) {
    /** Regular-season episodes that have aired by [todayEpochDay]. */
    fun airedEpisodes(todayEpochDay: Long): List<LibraryEpisodeRef> =
        episodes.filter { it.season > 0 && it.airEpochDay != null && it.airEpochDay <= todayEpochDay }

    /** Most recent release on or before [todayEpochDay] (drives the "New" sort). */
    fun latestReleaseDay(todayEpochDay: Long): Long? = releaseDays.lastOrNull { it <= todayEpochDay }
}

/** How long the Library's episode / release data is reused before it is re-read from the addons. */
internal const val LIBRARY_RELEASES_MAX_AGE_MS: Long = 3L * 60 * 60 * 1000

/** True once the last expansion is old enough that new episodes or seasons may have been announced. */
internal fun isLibraryRefreshDue(
    lastRefreshEpochMs: Long?,
    nowEpochMs: Long,
    maxAgeMs: Long = LIBRARY_RELEASES_MAX_AGE_MS,
): Boolean = lastRefreshEpochMs != null && nowEpochMs - lastRefreshEpochMs >= maxAgeMs

/**
 * Every numbered episode an addon lists for a title, with its air day when the addon gives one.
 * This is what moves a caught-up series back to Continue Watching once a new episode airs.
 */
internal fun libraryEpisodesOf(meta: MetaDetails?): List<LibraryEpisodeRef> =
    meta?.videos.orEmpty().mapNotNull { video ->
        val season = video.season ?: return@mapNotNull null
        val episode = video.episode ?: return@mapNotNull null
        LibraryEpisodeRef(season, episode, CalendarDay.parse(video.released)?.epochDay)
    }
