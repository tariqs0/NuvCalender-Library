package com.nuvio.app.features.calendar

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.isDesktop
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// ── Shared raw records ────────────────────────────────────────────────────────────────────────
// Sources emit raw records carrying every id they know; the merger de-duplicates on those ids
// (and on title + year as a last resort) before turning them into calendar entries.

internal data class GlobalShow(
    val preview: MetaPreview,
    val imdbId: String?,
    val tmdbId: Int?,
    val startYear: Int?,
    val facets: CalendarFacets = CalendarFacets(),
)

internal data class GlobalEpisode(
    val show: GlobalShow,
    val day: CalendarDay,
    val season: Int,
    val episode: Int,
    val title: String?,
    val thumbnail: String?,
    val videoId: String,
)

internal data class GlobalTitle(
    val preview: MetaPreview,
    val day: CalendarDay,
    val kind: CalendarEntryKind,
    val imdbId: String?,
    val tmdbId: Int?,
    val year: Int?,
    val sourceLabel: String?,
    val facets: CalendarFacets = CalendarFacets(),
)

private val calendarJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private val log = Logger.withTag("CalendarSources")

// ── Cinemeta ──────────────────────────────────────────────────────────────────────────────────

/**
 * Uses the Cinemeta addon (the installed one when present) the same way Stremio's calendar
 * does: popular / current-year series ids are expanded through the batched `calendar-videos`
 * catalog, which returns each show's recent and upcoming episodes in one request per 100 ids.
 */
internal object CinemetaCalendarSource {
    private const val CINEMETA_ID = "com.linvo.cinemeta"
    private const val DEFAULT_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"
    private const val PAGE_SIZE = 50
    private const val TOP_SERIES_PAGES = 12
    private const val YEAR_SERIES_PAGES = 6
    private const val MOVIE_PAGES = 4
    private const val CALENDAR_BATCH = 100

    private val mutex = Mutex()
    private var seriesCandidates: List<String>? = null
    private var movies: List<GlobalTitle>? = null
    private val showsById = mutableMapOf<String, CinemetaShow>()
    private val requestedIds = mutableSetOf<String>()

    internal data class CinemetaShow(val show: GlobalShow, val episodes: List<GlobalEpisode>) {
        /** Earliest dated episode; the batch only returns a window around today. */
        val windowStart: CalendarDay? = episodes.minOfOrNull { it.day }
    }

    fun manifestUrl(): String =
        AddonRepository.uiState.value.addons
            .firstOrNull { it.enabled && it.manifest?.id == CINEMETA_ID }
            ?.manifestUrl
            ?: DEFAULT_MANIFEST

    suspend fun cachedShows(): Map<String, CinemetaShow> = mutex.withLock { showsById.toMap() }

    /** Popular and current-year series ids, fetched once per session. */
    suspend fun seriesCandidateIds(today: CalendarDay): List<String> {
        mutex.withLock { seriesCandidates?.let { return it } }
        val requests = buildList {
            repeat(TOP_SERIES_PAGES) { add("top" to catalogExtra(genre = null, skip = it * PAGE_SIZE)) }
            listOf(today.year, today.year - 1).forEach { year ->
                repeat(YEAR_SERIES_PAGES) { add("year" to catalogExtra(genre = year.toString(), skip = it * PAGE_SIZE)) }
            }
        }
        val ids = coroutineScope {
            requests.map { (catalog, extra) -> async { fetchCatalog("series", catalog, extra) } }.awaitAll()
        }.flatten().mapNotNull { meta -> meta.imdbIdOrNull() }.distinct()
        mutex.withLock { if (ids.isNotEmpty()) seriesCandidates = ids }
        return ids
    }

    /** Expands ids not yet requested through `calendar-videos`; returns the full cache. */
    suspend fun expand(ids: Collection<String>): Map<String, CinemetaShow> {
        val pending = mutex.withLock {
            ids.filter { it.startsWith("tt") && it !in requestedIds }.distinct().also(requestedIds::addAll)
        }
        if (pending.isNotEmpty()) {
            val batches = pending.chunked(CALENDAR_BATCH)
            val semaphore = Semaphore(4)
            val results = coroutineScope {
                batches.map { batch ->
                    async { semaphore.withPermit { batch to fetchCalendarVideos(batch) } }
                }.awaitAll()
            }
            mutex.withLock {
                results.forEach { (batch, metas) ->
                    // A failed batch can be retried on the next refresh.
                    if (metas == null) requestedIds.removeAll(batch.toSet())
                    metas.orEmpty().forEach { meta -> meta.toCinemetaShow()?.let { showsById[it.show.imdbId!!] = it } }
                }
            }
        }
        return cachedShows()
    }

    /** Dated movies from Cinemeta's popular and current-year movie catalogs, once per session. */
    suspend fun datedMovies(today: CalendarDay): List<GlobalTitle> {
        mutex.withLock { movies?.let { return it } }
        val requests = buildList {
            repeat(MOVIE_PAGES) { add("top" to catalogExtra(null, it * PAGE_SIZE)) }
            listOf(today.year, today.year + 1, today.year - 1).forEach { year ->
                repeat(MOVIE_PAGES) { add("year" to catalogExtra(year.toString(), it * PAGE_SIZE)) }
            }
        }
        val result = coroutineScope {
            requests.map { (catalog, extra) -> async { fetchCatalog("movie", catalog, extra) } }.awaitAll()
        }.flatten().mapNotNull { it.toMovieTitle() }
        mutex.withLock { if (result.isNotEmpty()) movies = result }
        return result
    }

    fun reset() {
        seriesCandidates = null
        movies = null
        showsById.clear()
        requestedIds.clear()
    }

    private fun catalogExtra(genre: String?, skip: Int): String? = buildList {
        if (genre != null) add("genre=$genre")
        if (skip > 0) add("skip=$skip")
    }.joinToString("&").ifBlank { null }

    private suspend fun fetchCatalog(type: String, catalogId: String, extra: String?): List<CinemetaMeta> =
        runCatching {
            val url = buildAddonResourceUrl(manifestUrl(), "catalog", type, catalogId, extra)
            calendarJson.decodeFromString<CinemetaCatalogResponse>(httpGetText(url)).metas.filterNotNull()
        }.onFailure { log.w { "Cinemeta $type/$catalogId failed: ${it.message}" } }.getOrDefault(emptyList())

    /** Shows (with their dated episodes) from a `calendar-videos` response body. */
    internal fun parseCalendarVideos(body: String): List<CinemetaShow> =
        calendarJson.decodeFromString<CinemetaCalendarResponse>(body).metasDetailed
            .filterNotNull()
            .mapNotNull { it.toCinemetaShow() }

    private suspend fun fetchCalendarVideos(ids: List<String>): List<CinemetaMeta>? =
        runCatching {
            val url = buildAddonResourceUrl(
                manifestUrl = manifestUrl(),
                resource = "catalog",
                type = "series",
                id = "calendar-videos",
                extraPathSegment = "calendarVideosIds=${ids.joinToString(",")}",
            )
            calendarJson.decodeFromString<CinemetaCalendarResponse>(httpGetText(url)).metasDetailed.filterNotNull()
        }.onFailure { log.w { "Cinemeta calendar-videos failed: ${it.message}" } }.getOrNull()

    private fun CinemetaMeta.imdbIdOrNull(): String? =
        (imdbId ?: id).takeIf { it.startsWith("tt") }

    private fun CinemetaMeta.toPreview(type: String, imdb: String): MetaPreview = MetaPreview(
        id = imdb,
        type = type,
        name = name.orEmpty(),
        poster = poster,
        banner = background,
        logo = logo,
        description = description?.takeIf(String::isNotBlank),
        releaseInfo = releaseInfo ?: year.contentOrNull(),
        rawReleaseDate = released,
        popularity = popularities?.get("moviedb").doubleOrNull() ?: popularity.doubleOrNull(),
        imdbRating = imdbRating.contentOrNull()?.takeIf(String::isNotBlank),
        genres = genres.ifEmpty { genre },
        rawPosterUrl = poster,
    )

    private fun CinemetaMeta.facets(): CalendarFacets = CalendarFacets.of(
        genreNames = genres.ifEmpty { genre },
        language = language,
        countries = country?.split(',').orEmpty(),
    )

    private fun CinemetaMeta.toCinemetaShow(): CinemetaShow? {
        val imdb = imdbIdOrNull() ?: return null
        if (name.isNullOrBlank()) return null
        val show = GlobalShow(
            preview = toPreview("series", imdb),
            imdbId = imdb,
            tmdbId = moviedbId.intOrNullLenient(),
            startYear = leadingYear(year.contentOrNull() ?: releaseInfo ?: released),
            facets = facets(),
        )
        val episodes = videos.filterNotNull().mapNotNull { video ->
            val season = video.season.intOrNullLenient() ?: return@mapNotNull null
            val episode = video.episode.intOrNullLenient() ?: return@mapNotNull null
            val day = CalendarDay.parse(video.released ?: video.firstAired) ?: return@mapNotNull null
            GlobalEpisode(
                show = show,
                day = day,
                season = season,
                episode = episode,
                title = (video.name ?: video.title)?.takeIf(String::isNotBlank),
                thumbnail = video.thumbnail,
                videoId = video.id.ifBlank { "$imdb:$season:$episode" },
            )
        }
        return CinemetaShow(show, episodes)
    }

    private fun CinemetaMeta.toMovieTitle(): GlobalTitle? {
        val imdb = imdbIdOrNull() ?: return null
        val day = CalendarDay.parse(released) ?: return null
        if (name.isNullOrBlank()) return null
        return GlobalTitle(
            preview = toPreview("movie", imdb),
            day = day,
            kind = CalendarEntryKind.MovieRelease,
            imdbId = imdb,
            tmdbId = moviedbId.intOrNullLenient(),
            year = day.year,
            sourceLabel = "Cinemeta",
            facets = facets(),
        )
    }
}

// ── TMDB ──────────────────────────────────────────────────────────────────────────────────────

/**
 * TMDB discover finds every scripted show with an episode airing in a month (premieres,
 * returning seasons and weekly episodes), plus the month's movie releases.
 */
internal object TmdbCalendarSource {
    private const val LANGUAGE_SCOPED_PAGES = 20
    private const val ANY_LANGUAGE_PAGES = 3
    private const val PREMIERE_PAGES = 2
    private const val MOVIE_PAGES = 4
    private const val SERVICE_PAGES = 3
    private const val TMDB_MAX_PAGE = 500
    private const val SCRIPTED_TYPES = "2|4" // Miniseries | Scripted
    private const val EXCLUDED_GENRES = "10762|10763|10764|10766|10767" // Kids, News, Reality, Soap, Talk

    private val mutex = Mutex()
    private val monthShows = mutableMapOf<String, List<TmdbShowStub>>()
    private val monthMovies = mutableMapOf<String, List<GlobalTitle>>()
    private val details = mutableMapOf<String, TmdbShowDetails?>()
    private val seasons = mutableMapOf<String, List<TmdbEpisode>>()
    private val services = mutableMapOf<String, ServiceIndex>()
    private val titleInfos = mutableMapOf<String, TmdbTitleInfo>()

    /** Per-title TMDB data used by the Library: streaming services plus popularity and rating. */
    internal data class TmdbTitleInfo(
        val services: Set<CalendarStreamingService> = emptySet(),
        val popularity: Double? = null,
        val rating: Double? = null,
    )

    /** TMDB ids (TV and movie ids live in separate spaces) per streaming service. */
    internal data class ServiceIndex(
        val tv: Map<Int, Set<CalendarStreamingService>> = emptyMap(),
        val movies: Map<Int, Set<CalendarStreamingService>> = emptyMap(),
    )
    private val limiter = Semaphore(16)

    internal data class TmdbShowStub(val id: Int, val name: String, val startYear: Int?)

    fun reset() {
        services.clear()
        monthShows.clear()
        monthMovies.clear()
        details.clear()
        seasons.clear()
    }

    suspend fun airingShows(apiKey: String, month: CalendarDay, language: String): List<TmdbShowStub>? {
        val key = "${month.year}-${month.month}|$language"
        mutex.withLock { monthShows[key]?.let { return it } }
        val base = mapOf(
            "language" to language,
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "with_type" to SCRIPTED_TYPES,
            "without_genres" to EXCLUDED_GENRES,
        )
        val range = mapOf(
            "air_date.gte" to month.startOfMonth().toIsoString(),
            "air_date.lte" to month.endOfMonth().toIsoString(),
        )
        val premieres = mapOf(
            "first_air_date.gte" to month.startOfMonth().toIsoString(),
            "first_air_date.lte" to month.endOfMonth().toIsoString(),
        )
        val viewerLanguages = setOf("en", language.substringBefore('-').lowercase()).joinToString("|")
        val results = coroutineScope {
            listOf(
                async { discoverAll(apiKey, "discover/tv", base + range + ("with_original_language" to viewerLanguages), LANGUAGE_SCOPED_PAGES) },
                async { discoverAll(apiKey, "discover/tv", base + range, ANY_LANGUAGE_PAGES) },
                async { discoverAll(apiKey, "discover/tv", base + premieres, PREMIERE_PAGES) },
            ).awaitAll()
        }
        if (results.all { it == null }) return null
        val stubs = results.filterNotNull().flatten()
            .distinctBy { it.id }
            .mapNotNull { it.toStub() }
        mutex.withLock { monthShows[key] = stubs }
        return stubs
    }

    /**
     * Which TMDB ids in [month] belong to which streaming service: its originals (networks) plus
     * what streams there in [region] (watch providers).
     */
    suspend fun serviceIndex(apiKey: String, month: CalendarDay, region: String): ServiceIndex {
        val key = "${month.year}-${month.month}|$region"
        mutex.withLock { services[key]?.let { return it } }
        val perService = coroutineScope {
            CalendarStreamingService.entries.map { service ->
                async {
                    val providers = mapOf(
                        "with_watch_providers" to service.tmdbProviderIds.joinToString("|"),
                        "watch_region" to region,
                    )
                    val tvQuery = mapOf("sort_by" to "popularity.desc") + airDateRange(month)
                    val originals = discoverAll(
                        apiKey,
                        "discover/tv",
                        tvQuery + ("with_networks" to service.tmdbNetworkIds.joinToString("|")),
                        SERVICE_PAGES,
                    )
                    val streamingTv = discoverAll(apiKey, "discover/tv", tvQuery + providers, SERVICE_PAGES)
                    val streamingMovies = discoverAll(apiKey, "discover/movie", movieQuery(month, "en") + providers, SERVICE_PAGES)
                    Triple(
                        service,
                        (originals.orEmpty() + streamingTv.orEmpty()).map { it.id }.toSet(),
                        streamingMovies.orEmpty().map { it.id }.toSet(),
                    )
                }
            }.awaitAll()
        }
        fun index(pick: (Triple<CalendarStreamingService, Set<Int>, Set<Int>>) -> Set<Int>): Map<Int, Set<CalendarStreamingService>> {
            val result = mutableMapOf<Int, MutableSet<CalendarStreamingService>>()
            perService.forEach { entry -> pick(entry).forEach { id -> result.getOrPut(id) { mutableSetOf() }.add(entry.first) } }
            return result
        }
        val result = ServiceIndex(tv = index { it.second }, movies = index { it.third })
        mutex.withLock { services[key] = result }
        return result
    }

    /**
     * Streaming services of one title (used for Library entries, which the monthly index does
     * not cover): its original network plus what streams it in [region]. Cached per title.
     */
    suspend fun servicesForTitle(
        apiKey: String,
        tmdbId: Int,
        isMovie: Boolean,
        region: String,
    ): Set<CalendarStreamingService> = titleInfo(apiKey, tmdbId, isMovie, region).services

    suspend fun titleInfo(
        apiKey: String,
        tmdbId: Int,
        isMovie: Boolean,
        region: String,
    ): TmdbTitleInfo {
        val key = "${if (isMovie) "movie" else "tv"}:$tmdbId|$region"
        mutex.withLock { titleInfos[key]?.let { return it } }
        val response = fetch<TmdbTitleServices>(
            apiKey,
            "${if (isMovie) "movie" else "tv"}/$tmdbId",
            mapOf("append_to_response" to "watch/providers"),
        ) ?: return TmdbTitleInfo()
        val networkIds = response.networks.map { it.id }.toSet()
        val providerIds = response.watchProviders?.results?.get(region)
            ?.let { it.flatrate + it.ads + it.free }
            .orEmpty()
            .map { it.providerId }
            .toSet()
        val services = CalendarStreamingService.entries.filterTo(linkedSetOf()) { service ->
            service.tmdbNetworkIds.any(networkIds::contains) || service.tmdbProviderIds.any(providerIds::contains)
        }
        val result = TmdbTitleInfo(
            services = services,
            popularity = response.popularity,
            rating = response.voteAverage?.takeIf { (response.voteCount ?: 0) > 0 && it > 0.0 },
        )
        mutex.withLock { titleInfos[key] = result }
        return result
    }

    private fun scriptedQuery(language: String) = mapOf(
        "language" to language,
        "sort_by" to "popularity.desc",
        "include_adult" to "false",
        "with_type" to SCRIPTED_TYPES,
        "without_genres" to EXCLUDED_GENRES,
    )

    private fun airDateRange(month: CalendarDay) = mapOf(
        "air_date.gte" to month.startOfMonth().toIsoString(),
        "air_date.lte" to month.endOfMonth().toIsoString(),
    )

    private fun movieQuery(month: CalendarDay, language: String) = mapOf(
        "language" to language,
        "sort_by" to "popularity.desc",
        "include_adult" to "false",
        "primary_release_date.gte" to month.startOfMonth().toIsoString(),
        "primary_release_date.lte" to month.endOfMonth().toIsoString(),
    )

    private fun TmdbDiscoverItem.toStub(): TmdbShowStub? {
        val title = name?.takeIf(String::isNotBlank) ?: originalName ?: return null
        return TmdbShowStub(id, title, leadingYear(firstAirDate))
    }

    private fun TmdbDiscoverItem.toMovieTitle(): GlobalTitle? {
        val day = CalendarDay.parse(releaseDate) ?: return null
        val name = title?.takeIf(String::isNotBlank) ?: originalTitle ?: return null
        val poster = tmdbImage(posterPath, "w500")
        return GlobalTitle(
            preview = MetaPreview(
                id = "tmdb:$id",
                type = "movie",
                name = name,
                poster = poster,
                banner = tmdbImage(backdropPath, if (isDesktop) "w1280" else "w780"),
                description = overview?.takeIf(String::isNotBlank),
                releaseInfo = releaseDate?.take(4),
                rawReleaseDate = releaseDate,
                popularity = popularity,
                voteCount = voteCount,
                imdbRating = ratingText(),
                rawPosterUrl = poster,
            ),
            day = day,
            kind = CalendarEntryKind.MovieRelease,
            imdbId = null,
            tmdbId = id,
            year = day.year,
            sourceLabel = "TMDB",
            facets = CalendarFacets.of(
                genreNames = tmdbGenreNames(genreIds),
                language = originalLanguage,
                countries = originCountry,
            ),
        )
    }

    suspend fun movies(apiKey: String, month: CalendarDay, language: String): List<GlobalTitle>? {
        val key = "${month.year}-${month.month}|$language"
        mutex.withLock { monthMovies[key]?.let { return it } }
        val results = discoverAll(apiKey, "discover/movie", movieQuery(month, language), MOVIE_PAGES) ?: return null
        val titles = results.mapNotNull { it.toMovieTitle() }
        mutex.withLock { monthMovies[key] = titles }
        return titles
    }

    /** Show metadata plus its IMDb id (via external_ids) so it can be matched with Cinemeta. */
    suspend fun showDetails(apiKey: String, id: Int, language: String): TmdbShowDetails? {
        val key = "$id|$language"
        mutex.withLock { if (key in details) return details[key] }
        val result = fetch<TmdbShowDetails>(
            apiKey,
            "tv/$id",
            mapOf("language" to language, "append_to_response" to "external_ids"),
        )
        mutex.withLock { details[key] = result }
        return result
    }

    /** Dated episodes of [details] that can fall inside [month]. */
    suspend fun episodesFor(
        apiKey: String,
        details: TmdbShowDetails,
        show: GlobalShow,
        month: CalendarDay,
        language: String,
    ): List<GlobalEpisode> {
        val monthStart = month.startOfMonth()
        val monthEnd = month.endOfMonth()
        val dated = details.seasons
            .filter { it.seasonNumber > 0 }
            .mapNotNull { stub -> CalendarDay.parse(stub.airDate)?.let { stub.seasonNumber to it } }
            .sortedBy { it.first }
        val activeIndex = dated.indexOfLast { (_, airDate) -> airDate <= monthEnd }
        if (activeIndex < 0) return emptyList()
        val wanted = buildList {
            add(dated[activeIndex].first)
            // A season that started mid-month can share the month with the previous one.
            if (dated[activeIndex].second > monthStart && activeIndex > 0) add(dated[activeIndex - 1].first)
        }
        return wanted.flatMap { seasonNumber ->
            seasonEpisodes(apiKey, details.id, seasonNumber, language).mapNotNull { episode ->
                val day = CalendarDay.parse(episode.airDate) ?: return@mapNotNull null
                val season = episode.seasonNumber ?: seasonNumber
                GlobalEpisode(
                    show = show,
                    day = day,
                    season = season,
                    episode = episode.episodeNumber,
                    title = episode.name?.takeIf(String::isNotBlank),
                    thumbnail = tmdbImage(episode.stillPath, "w780"),
                    videoId = show.imdbId?.let { "$it:$season:${episode.episodeNumber}" }
                        ?: "tmdb:${details.id}:$season:${episode.episodeNumber}",
                )
            }
        }
    }

    fun TmdbShowDetails.toGlobalShow(): GlobalShow? {
        val title = name?.takeIf(String::isNotBlank) ?: originalName ?: return null
        val imdb = externalIds?.imdbId?.takeIf { it.startsWith("tt") }
        val poster = tmdbImage(posterPath, "w500")
        return GlobalShow(
            preview = MetaPreview(
                id = imdb ?: "tmdb:$id",
                type = "series",
                name = title,
                poster = poster,
                banner = tmdbImage(backdropPath, if (isDesktop) "w1280" else "w780"),
                description = overview?.takeIf(String::isNotBlank),
                releaseInfo = firstAirDate?.take(4),
                rawReleaseDate = firstAirDate,
                popularity = popularity,
                voteCount = voteCount,
                imdbRating = voteAverage?.takeIf { (voteCount ?: 0) > 0 && it > 0.0 }?.let(::formatRating),
                genres = canonicalGenres(tmdbGenreNames(genres.mapNotNull { it.id })).toList(),
                rawPosterUrl = poster,
            ),
            imdbId = imdb,
            tmdbId = id,
            startYear = leadingYear(firstAirDate),
            // Genre ids, not localized names, so filters match across languages.
            facets = CalendarFacets.of(
                genreNames = tmdbGenreNames(genres.mapNotNull { it.id }),
                language = originalLanguage,
                countries = originCountry,
            ),
        )
    }

    private suspend fun seasonEpisodes(apiKey: String, id: Int, season: Int, language: String): List<TmdbEpisode> {
        val key = "$id|$season|$language"
        mutex.withLock { seasons[key]?.let { return it } }
        val episodes = fetch<TmdbSeason>(apiKey, "tv/$id/season/$season", mapOf("language" to language))
            ?.episodes
            ?: return emptyList()
        mutex.withLock { seasons[key] = episodes }
        return episodes
    }

    private suspend fun discoverAll(
        apiKey: String,
        endpoint: String,
        query: Map<String, String>,
        maxPages: Int,
    ): List<TmdbDiscoverItem>? = discoverPages(apiKey, endpoint, query, firstPage = 1, pageCount = maxPages)?.first

    /** Pages [firstPage] until [firstPage] + [pageCount] (bounded by TMDB's total) and the total. */
    private suspend fun discoverPages(
        apiKey: String,
        endpoint: String,
        query: Map<String, String>,
        firstPage: Int,
        pageCount: Int,
    ): Pair<List<TmdbDiscoverItem>, Int?>? {
        val first = fetch<TmdbDiscoverPage>(apiKey, endpoint, query + ("page" to firstPage.toString())) ?: return null
        val lastPage = minOf(first.totalPages ?: firstPage, firstPage + pageCount - 1, TMDB_MAX_PAGE)
        if (lastPage <= firstPage) return first.results to first.totalPages
        val rest = coroutineScope {
            (firstPage + 1..lastPage).map { page ->
                async { fetch<TmdbDiscoverPage>(apiKey, endpoint, query + ("page" to page.toString()))?.results.orEmpty() }
            }.awaitAll()
        }
        return (first.results + rest.flatten()) to first.totalPages
    }

    private suspend inline fun <reified T> fetch(apiKey: String, endpoint: String, query: Map<String, String>): T? =
        limiter.withPermit {
            runCatching {
                calendarJson.decodeFromString<T>(httpGetText(buildTmdbUrl(endpoint, apiKey, query)))
            }.onFailure { log.w { "TMDB $endpoint failed: ${it.message}" } }.getOrNull()
        }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────

internal fun tmdbImage(path: String?, size: String): String? =
    path?.trim()?.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/$size$it" }

internal fun formatRating(value: Double): String = ((value * 10).toInt() / 10.0).toString()

private val YearRegex = Regex("""(19|20)\d{2}""")

internal fun leadingYear(raw: String?): Int? = raw?.let { YearRegex.find(it)?.value?.toIntOrNull() }

private fun JsonElement?.contentOrNull(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" }

private fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun JsonElement?.intOrNullLenient(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

private fun TmdbDiscoverItem.ratingText(): String? =
    voteAverage?.takeIf { (voteCount ?: 0) > 0 && it > 0.0 }?.let(::formatRating)

// ── Wire models ───────────────────────────────────────────────────────────────────────────────

@Serializable
private data class CinemetaCatalogResponse(val metas: List<CinemetaMeta?> = emptyList())

// Cinemeta returns `null` for ids it cannot resolve; one null must not drop the other 99 shows.
@Serializable
private data class CinemetaCalendarResponse(val metasDetailed: List<CinemetaMeta?> = emptyList())

@Serializable
private data class CinemetaMeta(
    val id: String = "",
    @SerialName("imdb_id") val imdbId: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val imdbRating: JsonElement? = null,
    val year: JsonElement? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    @SerialName("moviedb_id") val moviedbId: JsonElement? = null,
    val popularity: JsonElement? = null,
    val popularities: JsonObject? = null,
    val videos: List<CinemetaVideo?> = emptyList(),
)

@Serializable
private data class CinemetaVideo(
    val id: String = "",
    val season: JsonElement? = null,
    val episode: JsonElement? = null,
    val released: String? = null,
    val firstAired: String? = null,
    val name: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
)

@Serializable
private data class TmdbDiscoverPage(
    val results: List<TmdbDiscoverItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null,
)

@Serializable
private data class TmdbDiscoverItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
)

@Serializable
internal data class TmdbShowDetails(
    val id: Int,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    val popularity: Double? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    val seasons: List<TmdbSeasonStub> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable
private data class TmdbTitleServices(
    val networks: List<TmdbIdOnly> = emptyList(),
    @SerialName("watch/providers") val watchProviders: TmdbWatchProviders? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
)

@Serializable
private data class TmdbIdOnly(val id: Int)

@Serializable
private data class TmdbWatchProviders(val results: Map<String, TmdbRegionProviders> = emptyMap())

@Serializable
private data class TmdbRegionProviders(
    val flatrate: List<TmdbProviderRef> = emptyList(),
    val ads: List<TmdbProviderRef> = emptyList(),
    val free: List<TmdbProviderRef> = emptyList(),
)

@Serializable
private data class TmdbProviderRef(@SerialName("provider_id") val providerId: Int)

@Serializable
internal data class TmdbGenre(val id: Int? = null, val name: String? = null)

@Serializable
internal data class TmdbSeasonStub(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
internal data class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    val name: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
)
