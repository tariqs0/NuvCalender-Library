package com.nuvio.app.features.library

import com.nuvio.app.features.calendar.CalendarRefinement
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.calendar.leadingYear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibraryLayoutMode {
    HORIZONTAL,
    VERTICAL,
}

enum class LibrarySortOption {
    DEFAULT,
    ADDED_DESC,
    ADDED_ASC,
    YEAR_DESC,
    YEAR_ASC,
    TITLE_ASC,
    TITLE_DESC,

    /** Most recent episode or release first. */
    NEW,

    /** TMDB popularity. */
    TRENDING,
    RATING,
}

/**
 * Extra data the newer sorts need (release dates, popularity, rating), gathered by the Library
 * calendar sync. Empty context makes those sorts fall back to title order.
 */
internal data class LibrarySortContext(
    val titleInfo: Map<String, LibraryTitleInfo> = emptyMap(),
    val todayEpochDay: Long = 0L,
) {
    fun infoFor(item: LibraryItem): LibraryTitleInfo? = titleInfo[libraryDisplayItemKey(item)]

    fun yearOf(item: LibraryItem): Int? = leadingYear(item.releaseInfo) ?: infoFor(item)?.startYear
}

data class LibraryDisplaySettingsUiState(
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.HORIZONTAL,
    val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val refinement: LibraryRefinement = LibraryRefinement(),
)

object LibraryDisplaySettingsRepository {
    private val _uiState = MutableStateFlow(LibraryDisplaySettingsUiState())
    val uiState: StateFlow<LibraryDisplaySettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = LibraryDisplaySettingsUiState()
    }

    fun setLayoutMode(layoutMode: LibraryLayoutMode) {
        ensureLoaded()
        if (_uiState.value.layoutMode == layoutMode) return
        _uiState.value = _uiState.value.copy(layoutMode = layoutMode)
        persist()
    }

    fun setSortOption(sortOption: LibrarySortOption) {
        ensureLoaded()
        if (_uiState.value.sortOption == sortOption) return
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
        persist()
    }

    internal fun setRefinement(refinement: LibraryRefinement) {
        ensureLoaded()
        if (_uiState.value.refinement == refinement) return
        _uiState.value = _uiState.value.copy(refinement = refinement)
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = decodeLibraryDisplaySettings(LibraryDisplaySettingsStorage.loadPayload())
    }

    private fun persist() {
        LibraryDisplaySettingsStorage.savePayload(encodeLibraryDisplaySettings(_uiState.value))
    }
}

internal data class LibraryVerticalEntry(
    val item: LibraryItem,
    val section: LibrarySection,
)

internal data class LibraryVerticalProjection(
    val availableSections: List<LibrarySection>,
    val selectedSectionKey: String?,
    val availableTypes: List<String>,
    val selectedType: String?,
    val entries: List<LibraryVerticalEntry>,
)

internal fun availableLibrarySortOptions(sourceMode: LibrarySourceMode): List<LibrarySortOption> =
    if (sourceMode.isRemoteTrackingSource) {
        LibrarySortOption.entries
    } else {
        LibrarySortOption.entries.filterNot { it == LibrarySortOption.DEFAULT }
    }

internal fun effectiveLibrarySortOption(
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): LibrarySortOption =
    if (selected == LibrarySortOption.DEFAULT && sourceMode == LibrarySourceMode.LOCAL) {
        LibrarySortOption.ADDED_DESC
    } else {
        selected
    }

internal fun sortLibraryItems(
    items: List<LibraryItem>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    listKey: String? = null,
    providerOrder: Map<String, Int>? = null,
    context: LibrarySortContext = LibrarySortContext(),
): List<LibraryItem> =
    when (effectiveLibrarySortOption(selected, sourceMode)) {
        LibrarySortOption.DEFAULT -> items.sortedWith(
            if (sourceMode == LibrarySourceMode.MDBLIST) {
                compareByDescending<LibraryItem> { it.savedAtEpochMs }
                    .thenByDescending { it.listRanks[listKey] ?: Int.MIN_VALUE }
                    .thenBy { libraryTitleTieBreakKey(it) }
                    .thenBy { it.id }
            } else {
                compareBy<LibraryItem> { it.listRanks[listKey] ?: it.traktRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.savedAtEpochMs }
                    .thenBy { libraryTitleTieBreakKey(it) }
                    .thenBy { it.id }
            },
        )
        LibrarySortOption.ADDED_DESC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator) ?: compareByDescending<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_ASC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator) ?: compareBy<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_ASC -> items.sortedWith(
            compareBy<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        // Titles without the value sort last in both directions.
        LibrarySortOption.YEAR_DESC -> items.sortedWith(
            compareBy<LibraryItem> { if (context.yearOf(it) == null) 1 else 0 }
                .thenByDescending { context.yearOf(it) ?: 0 }
                .thenBy { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.YEAR_ASC -> items.sortedWith(
            compareBy<LibraryItem> { if (context.yearOf(it) == null) 1 else 0 }
                .thenBy { context.yearOf(it) ?: 0 }
                .thenBy { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.NEW -> items.sortedWith(
            compareByDescending<LibraryItem> {
                context.infoFor(it)?.latestReleaseDay(context.todayEpochDay) ?: Long.MIN_VALUE
            }
                .thenByDescending { context.yearOf(it) ?: 0 }
                .thenBy { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TRENDING -> items.sortedWith(
            compareByDescending<LibraryItem> { context.infoFor(it)?.popularity ?: -1.0 }
                .thenBy { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RATING -> items.sortedWith(
            compareByDescending<LibraryItem> {
                context.infoFor(it)?.rating ?: it.imdbRating?.toDoubleOrNull() ?: -1.0
            }
                .thenBy { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
    }

internal fun sortLibrarySections(
    sections: List<LibrarySection>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    providerOrders: Map<String, Map<String, Int>> = emptyMap(),
    context: LibrarySortContext = LibrarySortContext(),
): List<LibrarySection> =
    sections.map { section ->
        section.copy(
            items = sortLibraryItems(section.items, selected, sourceMode, section.type, providerOrders[section.type], context),
        )
    }

internal fun buildLibraryVerticalProjection(
    sections: List<LibrarySection>,
    sourceMode: LibrarySourceMode,
    selectedSectionKey: String?,
    selectedType: String?,
    sortOption: LibrarySortOption,
    providerOrders: Map<String, Map<String, Int>> = emptyMap(),
    context: LibrarySortContext = LibrarySortContext(),
): LibraryVerticalProjection {
    val availableSections = if (sourceMode.isRemoteTrackingSource) sections else emptyList()
    val selectedSection = if (sourceMode.isRemoteTrackingSource) {
        sections.firstOrNull { it.type == selectedSectionKey } ?: sections.firstOrNull()
    } else {
        null
    }
    val baseEntries = if (selectedSection != null) {
        selectedSection.items.map { item -> LibraryVerticalEntry(item, selectedSection) }
    } else {
        sections.flatMap { section ->
            section.items.map { item -> LibraryVerticalEntry(item, section) }
        }
    }
    val deduplicatedEntries = LinkedHashMap<String, LibraryVerticalEntry>()
    baseEntries.forEach { entry ->
        val key = libraryDisplayItemKey(entry.item)
        if (key !in deduplicatedEntries) {
            deduplicatedEntries[key] = entry
        }
    }
    val availableTypes = deduplicatedEntries.values
        .map { entry -> (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    val effectiveType = selectedType
        ?.normalizedLibraryType()
        ?.takeIf { it in availableTypes }
    val filteredEntries = deduplicatedEntries.values.filter { entry ->
        effectiveType == null || (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() == effectiveType
    }
    val entryByKey = filteredEntries.associateBy { entry -> libraryDisplayItemKey(entry.item) }
    val sortedEntries = sortLibraryItems(
        items = filteredEntries.map { entry -> entry.item },
        selected = sortOption,
        sourceMode = sourceMode,
        listKey = selectedSection?.type,
        providerOrder = providerOrders[selectedSection?.type],
        context = context,
    ).mapNotNull { item -> entryByKey[libraryDisplayItemKey(item)] }

    return LibraryVerticalProjection(
        availableSections = availableSections,
        selectedSectionKey = selectedSection?.type,
        availableTypes = availableTypes,
        selectedType = effectiveType,
        entries = sortedEntries,
    )
}

internal fun encodeLibraryDisplaySettings(state: LibraryDisplaySettingsUiState): String =
    LibraryDisplaySettingsJson.encodeToString(
        StoredLibraryDisplaySettings(
            layoutMode = state.layoutMode.name,
            sortOption = state.sortOption.name,
            genres = state.refinement.calendar.genres.toList(),
            languages = state.refinement.calendar.languages.toList(),
            countries = state.refinement.calendar.countries.toList(),
            services = state.refinement.calendar.services.toList(),
            decades = state.refinement.decades.toList(),
        ),
    )

internal fun decodeLibraryDisplaySettings(payload: String?): LibraryDisplaySettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            runCatching {
                LibraryDisplaySettingsJson.decodeFromString<StoredLibraryDisplaySettings>(value)
            }.getOrNull()
        }
    return LibraryDisplaySettingsUiState(
        layoutMode = stored?.layoutMode
            ?.let { value -> LibraryLayoutMode.entries.firstOrNull { it.name == value } }
            ?: LibraryLayoutMode.HORIZONTAL,
        sortOption = stored?.sortOption
            ?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
            ?: LibrarySortOption.DEFAULT,
        refinement = LibraryRefinement(
            calendar = CalendarRefinement(
                genres = stored?.genres.orEmpty().toSet(),
                languages = stored?.languages.orEmpty().toSet(),
                countries = stored?.countries.orEmpty().toSet(),
                services = stored?.services.orEmpty().toSet(),
            ),
            decades = stored?.decades.orEmpty().toSet(),
        ),
    )
}

private val LibraryDisplaySettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val LeadingLibraryTitleArticle = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun libraryTitleSortKey(item: LibraryItem): String =
    libraryTitleTieBreakKey(item)
        .trim()
        .replace(LeadingLibraryTitleArticle, "")

private fun libraryTitleTieBreakKey(item: LibraryItem): String =
    item.name
        .ifBlank { item.id }
        .lowercase()

internal fun libraryDisplayItemKey(item: LibraryItem): String =
    "${item.type.normalizedLibraryType()}:${item.id.trim()}"

private fun String.normalizedLibraryType(): String = trim().lowercase()

internal val LibrarySourceMode.isRemoteTrackingSource: Boolean
    get() = this != LibrarySourceMode.LOCAL

@Serializable
private data class StoredLibraryDisplaySettings(
    @SerialName("layout_mode") val layoutMode: String = LibraryLayoutMode.HORIZONTAL.name,
    @SerialName("sort_option") val sortOption: String = LibrarySortOption.DEFAULT.name,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("languages") val languages: List<String> = emptyList(),
    @SerialName("countries") val countries: List<String> = emptyList(),
    @SerialName("services") val services: List<String> = emptyList(),
    @SerialName("decades") val decades: List<Int> = emptyList(),
)

private fun libraryProviderOrderComparator(ranks: Map<String, Int>): Comparator<LibraryItem> =
    compareBy<LibraryItem> { ranks[libraryDisplayItemKey(it)] ?: Int.MAX_VALUE }.thenBy { it.id }
