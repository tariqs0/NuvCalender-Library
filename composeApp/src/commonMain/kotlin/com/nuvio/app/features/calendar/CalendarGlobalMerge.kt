package com.nuvio.app.features.calendar

/**
 * Turns raw records from every global source into calendar entries without duplicates.
 *
 * Sources are given in priority order; the first record to claim an identity wins. A record
 * claims every id it knows (IMDb, TMDB, normalised title + start year), so the same episode or
 * movie reported by Cinemeta, TMDB and a Home catalog is kept exactly once even when only some
 * of those ids overlap. Episodes are de-duplicated individually and only then grouped, so two
 * sources splitting a binge drop differently cannot double-count it.
 */
internal fun mergeGlobalCalendar(
    episodeSources: List<List<GlobalEpisode>>,
    titleSources: List<List<GlobalTitle>>,
    servicesFor: (isMovie: Boolean, tmdbId: Int?) -> Set<CalendarStreamingService> = { _, _ -> emptySet() },
): Map<Long, List<CalendarEntry>> {
    val claimed = HashSet<String>()
    fun claim(keys: List<String>): Boolean {
        if (keys.isEmpty() || keys.any(claimed::contains)) return false
        claimed.addAll(keys)
        return true
    }

    val episodes = episodeSources.flatten().filter { claim(it.identityKeys()) }
    val episodeEntries = episodes
        .groupBy { Triple(it.show.preview.id, it.day.epochDay, it.season) }
        .values
        .map { group ->
            val sorted = group.sortedBy { it.episode }
            val first = sorted.first()
            CalendarEntry(
                day = first.day,
                preview = first.show.preview,
                kind = when {
                    first.episode == 1 && first.season == 1 -> CalendarEntryKind.SeriesPremiere
                    first.episode == 1 && first.season > 1 -> CalendarEntryKind.SeasonPremiere
                    else -> CalendarEntryKind.Episode
                },
                episodes = sorted.map { episode ->
                    CalendarEpisode(
                        season = episode.season,
                        episode = episode.episode,
                        videoId = episode.videoId,
                        title = episode.title,
                    )
                },
                thumbnail = first.thumbnail,
                facets = first.show.facets.copy(services = servicesFor(false, first.show.tmdbId)),
            )
        }

    val titleEntries = titleSources.flatten()
        .filter { claim(it.identityKeys()) }
        .map { title ->
            CalendarEntry(
                day = title.day,
                preview = title.preview,
                kind = title.kind,
                sourceLabel = title.sourceLabel,
                facets = title.facets.copy(services = servicesFor(title.kind.isMovie, title.tmdbId)),
            )
        }

    return (episodeEntries + titleEntries).groupBy { it.day.epochDay }
}

internal fun normalizedTitle(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

internal fun titleYearKey(name: String, year: Int?): String = "${normalizedTitle(name)}|${year ?: ""}"

private fun GlobalEpisode.identityKeys(): List<String> =
    episodeKeys(show.imdbId, show.tmdbId, show.preview.name, show.startYear, season, episode)

/** A title-only series premiere is the show's S1E1, so it yields to a real episode record. */
private fun GlobalTitle.identityKeys(): List<String> =
    if (kind == CalendarEntryKind.SeriesPremiere) {
        episodeKeys(imdbId, tmdbId, preview.name, year, season = 1, episode = 1)
    } else {
        val tag = kind.name
        listOfNotNull(
            imdbId?.let { "i:$it:$tag" },
            tmdbId?.let { "t:$it:$tag" },
            year?.let { "n:${normalizedTitle(preview.name)}:$it:$tag" },
        )
    }

private fun episodeKeys(
    imdbId: String?,
    tmdbId: Int?,
    name: String,
    startYear: Int?,
    season: Int,
    episode: Int,
): List<String> = listOfNotNull(
    imdbId?.let { "i:$it:$season:$episode" },
    tmdbId?.let { "t:$it:$season:$episode" },
    // Same-named shows (e.g. two "Line of Fire" series) only collide when the start year matches too.
    startYear?.let { "n:${normalizedTitle(name)}:$it:$season:$episode" },
)
