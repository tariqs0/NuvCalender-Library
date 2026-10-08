package com.nuvio.app.features.library

import com.nuvio.app.features.calendar.CalendarFacets
import com.nuvio.app.features.calendar.CalendarRefinement
import com.nuvio.app.features.calendar.LibraryEpisodeRef
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.calendar.canonicalGenres
import com.nuvio.app.features.calendar.leadingYear
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemTypeAliases
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/** The automatic Library lists, in the order they are shown. */
enum class LibrarySmartList {
    ContinueWatching,
    Watchlist,
    Watched,
}

/**
 * Where a saved title belongs and how far along it is. Derived on the fly from watch progress,
 * watched history and episode air dates, so titles move between lists automatically: a finished
 * movie or fully watched series lands in Watched, and a series goes back to Continue Watching as
 * soon as a new episode or season airs.
 */
internal data class LibraryProgress(
    val list: LibrarySmartList,
    /** Most recently watched (or in-progress) episode. */
    val lastSeason: Int? = null,
    val lastEpisode: Int? = null,
    /** Playback progress of the most recent in-progress video, 0..1. */
    val progressFraction: Float? = null,
    /** Next aired episode not watched yet. */
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
    val lastActivityEpochMs: Long = 0L,
)

private const val MillisPerDay = 86_400_000L

internal fun classifyLibraryItem(
    item: LibraryItem,
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    fullyWatchedSeries: Boolean,
    info: LibraryTitleInfo?,
    todayEpochDay: Long,
): LibraryProgress {
    val inProgress = progressEntries
        .filter { !it.isEffectivelyCompleted && it.progressFraction > 0f }
        .maxByOrNull { it.lastUpdatedEpochMs }
    val latestProgress = progressEntries.maxByOrNull { it.lastUpdatedEpochMs }

    if (item.type.isMovieLike()) {
        val watched = watchedItems.isNotEmpty() || progressEntries.any { it.isEffectivelyCompleted }
        val list = when {
            watched -> LibrarySmartList.Watched
            inProgress != null -> LibrarySmartList.ContinueWatching
            else -> LibrarySmartList.Watchlist
        }
        return LibraryProgress(
            list = list,
            progressFraction = inProgress?.progressFraction?.takeIf { list == LibrarySmartList.ContinueWatching },
            lastActivityEpochMs = maxOf(
                watchedItems.maxOfOrNull { it.markedAtEpochMs } ?: 0L,
                latestProgress?.lastUpdatedEpochMs ?: 0L,
            ),
        )
    }

    // Series: individual watched episodes plus completed progress entries.
    val watchedEpisodes = buildSet {
        watchedItems.forEach { watched ->
            val season = watched.season ?: return@forEach
            val episode = watched.episode ?: return@forEach
            add(season to episode)
        }
        progressEntries.filter { it.isEffectivelyCompleted }.forEach { entry ->
            val season = entry.seasonNumber ?: return@forEach
            val episode = entry.episodeNumber ?: return@forEach
            add(season to episode)
        }
    }
    // A whole-series marker counts every episode aired by the time it was set.
    val seriesMarker = watchedItems.filter { it.season == null && it.episode == null }.maxByOrNull { it.markedAtEpochMs }
    val markerDay = seriesMarker?.let { if (it.markedAtEpochMs > 0L) it.markedAtEpochMs / MillisPerDay else Long.MAX_VALUE }
    fun LibraryEpisodeRef.isWatched(): Boolean =
        (season to episode) in watchedEpisodes ||
            (markerDay != null && airEpochDay != null && airEpochDay <= markerDay)

    val started = watchedEpisodes.isNotEmpty() || seriesMarker != null || progressEntries.isNotEmpty() || fullyWatchedSeries
    val aired = info?.airedEpisodes(todayEpochDay).orEmpty().sortedWith(compareBy({ it.season }, { it.episode }))
    val nextUnwatched = aired.firstOrNull { !it.isWatched() }

    val list = when {
        !started -> LibrarySmartList.Watchlist
        aired.isNotEmpty() -> if (nextUnwatched == null && inProgress == null) {
            LibrarySmartList.Watched
        } else {
            LibrarySmartList.ContinueWatching
        }
        // No air dates from the addon: fall back to the provider's fully-watched flag.
        fullyWatchedSeries || seriesMarker != null -> LibrarySmartList.Watched
        else -> LibrarySmartList.ContinueWatching
    }

    // The last thing watched: the newest of a watched episode and a progress entry.
    val lastWatchedEpisode = watchedItems.filter { it.season != null && it.episode != null }.maxByOrNull { it.markedAtEpochMs }
    val progressIsNewer = latestProgress != null &&
        latestProgress.lastUpdatedEpochMs >= (lastWatchedEpisode?.markedAtEpochMs ?: Long.MIN_VALUE)
    val (lastSeason, lastEpisode) = when {
        progressIsNewer && latestProgress?.seasonNumber != null -> latestProgress.seasonNumber to latestProgress.episodeNumber
        lastWatchedEpisode != null -> lastWatchedEpisode.season to lastWatchedEpisode.episode
        else -> null to null
    }
    return LibraryProgress(
        list = list,
        lastSeason = lastSeason,
        lastEpisode = lastEpisode,
        progressFraction = inProgress?.progressFraction,
        nextSeason = nextUnwatched?.season?.takeIf { list == LibrarySmartList.ContinueWatching },
        nextEpisode = nextUnwatched?.episode?.takeIf { list == LibrarySmartList.ContinueWatching },
        lastActivityEpochMs = maxOf(
            watchedItems.maxOfOrNull { it.markedAtEpochMs } ?: 0L,
            latestProgress?.lastUpdatedEpochMs ?: 0L,
        ),
    )
}

/** Watched history and progress of one Library title (matching across movie / series aliases). */
internal fun WatchedItem.belongsTo(item: LibraryItem): Boolean =
    id == item.id && type.trim().lowercase() in watchedItemTypeAliases(item.type)

internal fun WatchProgressEntry.belongsTo(item: LibraryItem): Boolean =
    parentMetaId == item.id

private fun String.isMovieLike(): Boolean = trim().lowercase().let { it == "movie" || it == "film" }

// ── Filters (same facets and order as the Calendar) ─────────────────────────────────────────

/** Genre / language / country / streaming-service filters shared with the Calendar, plus decades. */
data class LibraryRefinement(
    val calendar: CalendarRefinement = CalendarRefinement(),
    /** Decade starts, e.g. 2020 for the 2020s. */
    val decades: Set<Int> = emptySet(),
) {
    val activeCount: Int get() = calendar.activeCount + decades.size
}

/** Facets of a Library title: synced metadata when available, else what the Library item holds. */
internal fun LibraryItem.libraryFacets(info: LibraryTitleInfo?): CalendarFacets =
    info?.facets?.let { facets ->
        if (facets.genres.isEmpty() && genres.isNotEmpty()) facets.copy(genres = canonicalGenres(genres)) else facets
    } ?: CalendarFacets(genres = canonicalGenres(genres))

internal fun LibraryItem.releaseYear(info: LibraryTitleInfo?): Int? = leadingYear(releaseInfo) ?: info?.startYear

internal fun decadeOf(year: Int): Int = year - year % 10

internal fun LibraryRefinement.matches(item: LibraryItem, info: LibraryTitleInfo?): Boolean {
    if (!calendar.matches(item.libraryFacets(info))) return false
    if (decades.isEmpty()) return true
    val year = item.releaseYear(info) ?: return false
    return decadeOf(year) in decades
}

/** Content type of a title as the Library groups it (anime and addon categories included). */
internal fun LibraryItem.libraryContentType(): String = (mediaCategory ?: type).trim().lowercase()
