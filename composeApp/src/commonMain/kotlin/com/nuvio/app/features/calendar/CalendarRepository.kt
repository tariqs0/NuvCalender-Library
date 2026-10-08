package com.nuvio.app.features.calendar

import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.library.LibraryHiddenRepository
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Feeds both calendars.
 *
 * - Global: Cinemeta `calendar-videos` (episodes of popular, current-year, catalog and library
 *   series), TMDB discover (every scripted show airing in a month, premieres and movie releases),
 *   Cinemeta's dated movie catalogs and every dated item on Home — de-duplicated by IMDb id,
 *   TMDB id and title + year.
 * - Personal: every Library / Watchlist title, expanded into episode, season and movie release
 *   dates through the same meta pipeline the details screen uses.
 */
internal object CalendarRepository {
    private const val META_FETCH_CONCURRENCY = 4
    private const val MANIFEST_WAIT_MS = 10_000L
    private const val TMDB_SHOW_BATCH = 40

    private val log = Logger.withTag("CalendarRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    // ── Global ────────────────────────────────────────────────────────────────────────────────
    private data class GlobalSources(
        val cinemetaShows: Map<String, CinemetaCalendarSource.CinemetaShow> = emptyMap(),
        val cinemetaMovies: List<GlobalTitle> = emptyList(),
        val tmdbEpisodes: Map<String, List<GlobalEpisode>> = emptyMap(),
        val tmdbMovies: Map<String, List<GlobalTitle>> = emptyMap(),
        val tvServices: Map<Int, Set<CalendarStreamingService>> = emptyMap(),
        val movieServices: Map<Int, Set<CalendarStreamingService>> = emptyMap(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val failed: Boolean = false,
    )

    private val globalMutex = Mutex()
    private val loadedMonths = mutableSetOf<String>()
    private val loadingMonths = mutableSetOf<String>()
    private val _globalSources = MutableStateFlow(GlobalSources())

    val globalState: StateFlow<CalendarFeedState> =
        combine(_globalSources, HomeRepository.uiState) { sources, home ->
            CalendarFeedState(
                entriesByDay = mergeGlobalCalendar(
                    // Earlier sources win: addon-native (IMDb) ids first, TMDB-only ids last.
                    episodeSources = listOf(
                        sources.cinemetaShows.values.flatMap { it.episodes },
                        sources.tmdbEpisodes.values.flatten(),
                    ),
                    titleSources = listOf(
                        catalogTitles(home),
                        sources.cinemetaMovies,
                        sources.tmdbMovies.values.flatten(),
                    ),
                    servicesFor = { isMovie, tmdbId ->
                        tmdbId?.let { (if (isMovie) sources.movieServices else sources.tvServices)[it] }.orEmpty()
                    },
                ),
                isLoading = sources.isLoading,
                hasLoaded = sources.hasLoaded,
                errorMessage = if (sources.failed) "global" else null,
            )
        }.stateIn(scope, SharingStarted.Eagerly, CalendarFeedState())

    /** Loads every month the [range] touches that has not been loaded yet (in this language). */
    fun ensureGlobalRange(range: CalendarRange) {
        val language = tmdbLanguage()
        val months = monthsCovering(range).map { (year, month) -> CalendarDay.of(year, month, 1) }
        scope.launch {
            val pending = globalMutex.withLock {
                months.filter { month ->
                    val key = monthKey(month.year, month.month, language)
                    key !in loadedMonths && key !in loadingMonths
                }.also { list -> list.forEach { loadingMonths += monthKey(it.year, it.month, language) } }
            }
            if (pending.isEmpty()) return@launch
            _globalSources.update { it.copy(isLoading = true, failed = false) }
            try {
                loadGlobalMonths(pending, language)
            } finally {
                globalMutex.withLock {
                    pending.forEach { month ->
                        val key = monthKey(month.year, month.month, language)
                        loadingMonths -= key
                        loadedMonths += key
                    }
                    val stillLoading = loadingMonths.isNotEmpty()
                    _globalSources.update { sources ->
                        sources.copy(
                            isLoading = stillLoading,
                            hasLoaded = true,
                            failed = !stillLoading && sources.cinemetaShows.isEmpty() &&
                                sources.cinemetaMovies.isEmpty() && sources.tmdbMovies.values.all { it.isEmpty() },
                        )
                    }
                }
            }
        }
    }

    fun refreshGlobal(range: CalendarRange) {
        scope.launch {
            globalMutex.withLock {
                loadedMonths.clear()
                CinemetaCalendarSource.reset()
                TmdbCalendarSource.reset()
                _globalSources.value = GlobalSources(isLoading = true)
            }
            ensureGlobalRange(range)
        }
    }

    private suspend fun loadGlobalMonths(months: List<CalendarDay>, language: String) {
        AddonRepository.initialize()
        withTimeoutOrNull(MANIFEST_WAIT_MS) { AddonRepository.awaitManifestsLoaded() }
        val today = CalendarDay.today()

        // 1. Cinemeta: popular + current-year series and every series id the user already sees.
        val candidates = CinemetaCalendarSource.seriesCandidateIds(today) + knownSeriesIds()
        val (shows, movies) = coroutineScope {
            val shows = async { CinemetaCalendarSource.expand(candidates) }
            val movies = async { CinemetaCalendarSource.datedMovies(today) }
            shows.await() to movies.await()
        }
        _globalSources.update { it.copy(cinemetaShows = shows, cinemetaMovies = movies) }

        // 2. TMDB: everything airing in each month that Cinemeta does not already cover.
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return
        coroutineScope {
            months.map { month -> async { loadTmdbMonth(apiKey, month, language) } }.awaitAll()
        }
    }

    private suspend fun loadTmdbMonth(apiKey: String, month: CalendarDay, language: String) {
        val key = monthKey(month.year, month.month, language)
        _globalSources.update { it.copy(tmdbEpisodes = it.tmdbEpisodes - key) }
        val movies = TmdbCalendarSource.movies(apiKey, month, language).orEmpty()
        _globalSources.update { it.copy(tmdbMovies = it.tmdbMovies + (key to movies)) }

        val stubs = TmdbCalendarSource.airingShows(apiKey, month, language).orEmpty()
        expandTmdbShows(apiKey, month, language, key, stubs)

        // Streaming services last, so the month's releases appear before their service tags.
        val services = TmdbCalendarSource.serviceIndex(apiKey, month, viewerRegion())
        _globalSources.update {
            it.copy(
                tvServices = it.tvServices.mergedWith(services.tv),
                movieServices = it.movieServices.mergedWith(services.movies),
            )
        }
    }

    private fun Map<Int, Set<CalendarStreamingService>>.mergedWith(
        other: Map<Int, Set<CalendarStreamingService>>,
    ): Map<Int, Set<CalendarStreamingService>> {
        val merged = toMutableMap()
        other.forEach { (id, services) -> merged[id] = merged[id].orEmpty() + services }
        return merged
    }

    /** Expands TMDB shows not already covered by Cinemeta into episodes, a batch at a time. */
    private suspend fun expandTmdbShows(
        apiKey: String,
        month: CalendarDay,
        language: String,
        key: String,
        stubs: List<TmdbCalendarSource.TmdbShowStub>,
    ) {
        val monthStart = month.startOfMonth()
        fun coveredByCinemeta(show: CinemetaCalendarSource.CinemetaShow?): Boolean =
            show?.windowStart != null && show.windowStart <= monthStart

        val cinemetaByTitle = _globalSources.value.cinemetaShows.values
            .associateBy { titleYearKey(it.show.preview.name, it.show.startYear) }
        val uncovered = stubs.filterNot { stub ->
            coveredByCinemeta(cinemetaByTitle[titleYearKey(stub.name, stub.startYear)])
        }
        // Popular shows first, published batch by batch so the month fills in progressively.
        uncovered.chunked(TMDB_SHOW_BATCH).forEach { batch ->
            val details = coroutineScope {
                batch.map { stub -> async { TmdbCalendarSource.showDetails(apiKey, stub.id, language) } }.awaitAll()
            }.filterNotNull()

            // Newly learned IMDb ids go through one batched Cinemeta call instead of per-season TMDB calls.
            val cinemeta = CinemetaCalendarSource.expand(details.mapNotNull { it.externalIds?.imdbId })
            val episodes = coroutineScope {
                details.map { detail ->
                    async {
                        with(TmdbCalendarSource) {
                            val show = detail.toGlobalShow() ?: return@async emptyList()
                            if (coveredByCinemeta(show.imdbId?.let(cinemeta::get))) return@async emptyList()
                            episodesFor(apiKey, detail, show, month, language)
                        }
                    }
                }.awaitAll()
            }.flatten()
            _globalSources.update {
                it.copy(
                    cinemetaShows = cinemeta,
                    tmdbEpisodes = it.tmdbEpisodes + (key to it.tmdbEpisodes[key].orEmpty() + episodes),
                )
            }
        }
    }

    /** IMDb ids of series on Home and in the Library, so those shows are always on the calendar. */
    private fun knownSeriesIds(): List<String> {
        val home = HomeRepository.uiState.value
        val homeIds = (home.heroItems + home.sections.flatMap { it.items })
            .filterNot { it.type.isMovieType() }
            .map { it.id }
        val libraryIds = LibraryRepository.uiState.value.items
            .filterNot { it.type.isMovieType() }
            .mapNotNull { item -> item.imdbId ?: item.id }
        return (homeIds + libraryIds).filter { it.startsWith("tt") }.distinct()
    }

    private fun catalogTitles(home: HomeUiState): List<GlobalTitle> {
        val previews = home.heroItems.map { it to null } +
            home.sections.flatMap { section -> section.items.map { it to section.addonName } }
        return previews.mapNotNull { (preview, addonName) ->
            // Year-only release info cannot be placed on a day, so only real dates qualify.
            val day = CalendarDay.parse(preview.rawReleaseDate)
                ?: CalendarDay.parse(preview.releaseInfo?.takeIf { it.length >= 10 })
                ?: return@mapNotNull null
            GlobalTitle(
                preview = preview,
                day = day,
                kind = if (preview.type.isMovieType()) {
                    CalendarEntryKind.MovieRelease
                } else {
                    CalendarEntryKind.SeriesPremiere
                },
                imdbId = preview.id.takeIf { it.startsWith("tt") },
                tmdbId = preview.id.removePrefix("tmdb:").takeIf { preview.id.startsWith("tmdb:") }?.toIntOrNull(),
                year = day.year,
                sourceLabel = addonName,
                facets = CalendarFacets.of(genreNames = preview.genres),
            )
        }
    }

    // ── Personal ──────────────────────────────────────────────────────────────────────────────
    private val personalMutex = Mutex()
    private val personalByTitle = mutableMapOf<String, List<CalendarEntry>>()
    private val personalInfoByTitle = mutableMapOf<String, LibraryTitleInfo>()
    private val _personalState = MutableStateFlow(CalendarFeedState())
    private val _libraryTitleInfo = MutableStateFlow<Map<String, LibraryTitleInfo>>(emptyMap())

    /** The Library calendar; titles in the Hidden list never appear here. */
    val personalState: StateFlow<CalendarFeedState> =
        combine(_personalState, LibraryHiddenRepository.uiState) { feed, hidden ->
            if (hidden.hiddenKeys.isEmpty()) {
                feed
            } else {
                feed.copy(
                    entriesByDay = feed.entriesByDay
                        .mapValues { (_, entries) -> entries.filterNot { hidden.isHidden(it.preview.type, it.preview.id) } }
                        .filterValues { it.isNotEmpty() },
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, CalendarFeedState())

    /**
     * Metadata the Library page filters and sorts on (genres, country, language, streaming
     * services, popularity, rating) plus every dated episode / release, keyed by "type:id".
     */
    val libraryTitleInfo: StateFlow<Map<String, LibraryTitleInfo>> = _libraryTitleInfo.asStateFlow()
    private var personalJob: Job? = null
    private var personalRefreshedAtMs: Long? = null

    /**
     * Starts following the library; titles are expanded once and re-used until refreshed.
     * Once the expansion is older than [LIBRARY_RELEASES_MAX_AGE_MS] it is re-read from the
     * addons, so newly announced episodes and seasons appear while the app stays open.
     */
    fun ensurePersonalLoaded() {
        if (personalJob?.isActive == true) {
            val now = LibraryClock.nowEpochMs()
            if (isLibraryRefreshDue(personalRefreshedAtMs, now)) {
                personalRefreshedAtMs = now
                refreshPersonal()
            }
            return
        }
        LibraryRepository.ensureLoaded()
        personalJob = scope.launch {
            LibraryRepository.uiState
                .map { state -> state.isLoaded to state.items.distinctBy(::titleKey) }
                .distinctUntilChanged()
                .collectLatest { (isLoaded, items) ->
                    if (isLoaded) syncPersonal(items, force = false)
                }
        }
    }

    fun refreshPersonal() {
        scope.launch {
            LibraryRepository.ensureLoaded()
            syncPersonal(LibraryRepository.uiState.value.items.distinctBy(::titleKey), force = true)
        }
    }

    private suspend fun syncPersonal(items: List<LibraryItem>, force: Boolean) {
        val wanted = items.associateBy(::titleKey)
        val missing = personalMutex.withLock {
            personalByTitle.keys.retainAll(wanted.keys)
            personalInfoByTitle.keys.retainAll(wanted.keys)
            if (personalByTitle.isEmpty() || force) personalRefreshedAtMs = LibraryClock.nowEpochMs()
            // A refresh re-reads every title but keeps the current data on screen until replaced.
            if (force) wanted.values.toList() else wanted.filterKeys { it !in personalByTitle }.values.toList()
        }
        publishPersonal(isLoading = missing.isNotEmpty())
        if (missing.isEmpty()) return

        AddonRepository.initialize()
        withTimeoutOrNull(MANIFEST_WAIT_MS) { AddonRepository.awaitManifestsLoaded() }

        val semaphore = Semaphore(META_FETCH_CONCURRENCY)
        missing.map { item ->
            scope.async {
                semaphore.withPermit {
                    val built = runCatching { buildPersonalEntries(item, fresh = force) }
                        .onFailure { log.w { "Calendar expansion failed for ${item.type}:${item.id}: ${it.message}" } }
                        .getOrNull()
                    personalMutex.withLock {
                        personalByTitle[titleKey(item)] = built?.first.orEmpty()
                        built?.second?.let { personalInfoByTitle[titleKey(item)] = it }
                    }
                    publishPersonal(isLoading = true)
                }
            }
        }.awaitAll()
        publishPersonal(isLoading = false)
    }

    private suspend fun publishPersonal(isLoading: Boolean) {
        val (entries, infos) = personalMutex.withLock { personalByTitle.values.flatten() to personalInfoByTitle.toMap() }
        _libraryTitleInfo.value = infos
        _personalState.value = CalendarFeedState(
            entriesByDay = entries.groupBy { it.day.epochDay },
            isLoading = isLoading,
            hasLoaded = !isLoading || entries.isNotEmpty(),
        )
    }

    private suspend fun buildPersonalEntries(
        item: LibraryItem,
        fresh: Boolean,
    ): Pair<List<CalendarEntry>, LibraryTitleInfo> {
        val meta = MetaDetailsRepository.fetch(type = item.type, id = item.id, cacheResult = false, readCache = !fresh)
        val preview = item.toMetaPreview().let { base ->
            if (meta == null) base else base.copy(
                banner = base.banner ?: meta.background,
                logo = base.logo ?: meta.logo,
                description = base.description ?: meta.description,
                genres = base.genres.ifEmpty { meta.genres },
                imdbRating = base.imdbRating ?: meta.imdbRating,
            )
        }
        val tmdb = libraryTmdbInfo(item, meta)
        val facets = CalendarFacets.of(
            genreNames = preview.genres + listOfNotNull("Anime".takeIf { item.isAnime }),
            language = meta?.language,
            countries = meta?.country?.split(',').orEmpty(),
        ).copy(services = tmdb.services)
        val entries = if (item.type.isMovieType()) {
            movieEntries(item, meta, preview, facets)
        } else {
            seriesEntries(meta, preview, facets)
        }
        val info = LibraryTitleInfo(
            facets = facets,
            popularity = tmdb.popularity,
            rating = tmdb.rating ?: (preview.imdbRating ?: meta?.imdbRating)?.toDoubleOrNull(),
            startYear = leadingYear(item.releaseInfo ?: meta?.releaseInfo),
            episodes = meta?.videos.orEmpty().mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                LibraryEpisodeRef(season, episode, CalendarDay.parse(video.released)?.epochDay)
            },
            releaseDays = entries.map { it.day.epochDay }.distinct().sorted(),
        )
        return entries to info
    }

    /** Streaming services, popularity and rating for a Library title, so it filters like Global. */
    private suspend fun libraryTmdbInfo(item: LibraryItem, meta: MetaDetails?): TmdbCalendarSource.TmdbTitleInfo {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank)
            ?: return TmdbCalendarSource.TmdbTitleInfo()
        val isMovie = item.type.isMovieType()
        val tmdbId = item.tmdbId
            ?: runCatching {
                TmdbService.ensureTmdbId(item.id, if (isMovie) "movie" else "tv", item.imdbId ?: meta?.imdbId)
            }.getOrNull()?.toIntOrNull()
            ?: return TmdbCalendarSource.TmdbTitleInfo()
        return TmdbCalendarSource.titleInfo(apiKey, tmdbId, isMovie, viewerRegion())
    }

    /** Tracking providers tag anime via [LibraryItem.mediaCategory]; anime addons via their id scheme. */
    private val LibraryItem.isAnime: Boolean
        get() = mediaCategory.equals("anime", ignoreCase = true) ||
            type.equals("anime", ignoreCase = true) ||
            AnimeIdPrefixes.any { id.startsWith(it) }

    private fun seriesEntries(meta: MetaDetails?, preview: MetaPreview, facets: CalendarFacets): List<CalendarEntry> {
        meta ?: return emptyList()
        val dated = meta.videos.mapNotNull { video ->
            val day = CalendarDay.parse(video.released) ?: return@mapNotNull null
            if (video.season == null && video.episode == null) return@mapNotNull null
            day to video
        }
        val firstRegularSeason = dated.mapNotNull { it.second.season }.filter { it > 0 }.minOrNull()

        // Collapse binge drops (several episodes on one day) into a single calendar entry.
        return dated
            .groupBy { (day, video) -> day.epochDay to (video.season ?: 0) }
            .map { (_, group) ->
                val sorted = group.sortedBy { it.second.episode ?: 0 }
                val (day, first) = sorted.first()
                val season = first.season
                val kind = when {
                    first.episode == 1 && season != null && season == firstRegularSeason ->
                        CalendarEntryKind.SeriesPremiere
                    first.episode == 1 && season != null && season > 0 -> CalendarEntryKind.SeasonPremiere
                    else -> CalendarEntryKind.Episode
                }
                CalendarEntry(
                    day = day,
                    preview = preview,
                    kind = kind,
                    episodes = sorted.map { (_, video) ->
                        CalendarEpisode(
                            season = video.season,
                            episode = video.episode,
                            videoId = video.id,
                            title = video.title,
                        )
                    },
                    thumbnail = first.thumbnail ?: first.seasonPoster,
                    facets = facets,
                )
            }
    }

    private suspend fun movieEntries(
        item: LibraryItem,
        meta: MetaDetails?,
        preview: MetaPreview,
        facets: CalendarFacets,
    ): List<CalendarEntry> {
        val tmdbRelease = fetchTmdbMovieRelease(item, meta)
        val primary = tmdbRelease?.primary
            ?: CalendarDay.parse(item.releaseInfo?.takeIf { it.length >= 10 })
            ?: CalendarDay.parse(meta?.releaseInfo?.takeIf { it.length >= 10 })
            ?: meta?.videos?.firstNotNullOfOrNull { CalendarDay.parse(it.released) }
        return buildList {
            primary?.let {
                add(CalendarEntry(day = it, preview = preview, kind = CalendarEntryKind.MovieRelease, facets = facets))
            }
            tmdbRelease?.digital
                ?.takeIf { it != primary }
                ?.let {
                    add(
                        CalendarEntry(day = it, preview = preview, kind = CalendarEntryKind.MovieDigitalRelease, facets = facets),
                    )
                }
        }
    }

    private suspend fun fetchTmdbMovieRelease(item: LibraryItem, meta: MetaDetails?): MovieReleaseDates? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return null
        val tmdbId = item.tmdbId?.toString()
            ?: TmdbService.ensureTmdbId(item.id, "movie", item.imdbId ?: meta?.imdbId)
            ?: return null
        val response = runCatching {
            json.decodeFromString<CalendarMovieResponse>(
                httpGetText(
                    buildTmdbUrl(
                        endpoint = "movie/$tmdbId",
                        apiKey = apiKey,
                        query = mapOf("append_to_response" to "release_dates"),
                    ),
                ),
            )
        }.onFailure { log.w { "TMDB movie/$tmdbId failed: ${it.message}" } }.getOrNull() ?: return null

        val region = preferredRegion()
        val byCountry = response.releaseDates?.results.orEmpty()
        val regional = byCountry.firstOrNull { it.country.equals(region, ignoreCase = true) }?.releaseDates
        val digitalDates = (regional ?: byCountry.flatMap { it.releaseDates })
            .filter { it.type == TMDB_RELEASE_TYPE_DIGITAL }
            .mapNotNull { CalendarDay.parse(it.releaseDate) }
        return MovieReleaseDates(
            primary = CalendarDay.parse(response.releaseDate),
            digital = digitalDates.minOrNull(),
        )
    }

    private data class MovieReleaseDates(val primary: CalendarDay?, val digital: CalendarDay?)

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────
    private fun titleKey(item: LibraryItem): String = "${item.type.trim().lowercase()}:${item.id.trim()}"

    private fun tmdbLanguage(): String =
        TmdbSettingsRepository.snapshot().language.ifBlank { "en" }

    /** Country used for "where to watch" (device region; US when unknown). */
    private fun viewerRegion(): String =
        androidx.compose.ui.text.intl.Locale.current.region.uppercase().takeIf { it.length == 2 } ?: "US"

    private fun preferredRegion(): String =
        tmdbLanguage().substringAfter('-', missingDelimiterValue = "").takeIf { it.length == 2 } ?: "US"

    private fun monthKey(year: Int, month: Int, language: String) = "$year-$month|$language"

    private fun monthsCovering(range: CalendarRange): List<Pair<Int, Int>> {
        val months = mutableListOf<Pair<Int, Int>>()
        var cursor = range.start.startOfMonth()
        while (cursor <= range.endInclusive) {
            months += cursor.year to cursor.month
            cursor = cursor.plusMonths(1)
        }
        return months
    }

    private const val TMDB_RELEASE_TYPE_DIGITAL = 4
    private val AnimeIdPrefixes = listOf("kitsu:", "mal:", "anilist:", "anidb:")
}

internal fun String.isMovieType(): Boolean = trim().lowercase().let { it == "movie" || it == "film" }

@Serializable
private data class CalendarMovieResponse(
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("release_dates") val releaseDates: CalendarReleaseDatesWrapper? = null,
)

@Serializable
private data class CalendarReleaseDatesWrapper(
    val results: List<CalendarCountryReleaseDates> = emptyList(),
)

@Serializable
private data class CalendarCountryReleaseDates(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<CalendarReleaseDate> = emptyList(),
)

@Serializable
private data class CalendarReleaseDate(
    @SerialName("release_date") val releaseDate: String? = null,
    val type: Int? = null,
)
