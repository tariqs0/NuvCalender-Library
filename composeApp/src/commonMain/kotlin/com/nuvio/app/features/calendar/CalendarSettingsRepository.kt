package com.nuvio.app.features.calendar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class CalendarSettingsUiState(
    val globalCalendarEnabled: Boolean = true,
    val viewMode: CalendarViewMode = CalendarViewMode.Month,
    val scope: CalendarScope = CalendarScope.Global,
    val contentFilter: CalendarContentFilter = CalendarContentFilter.All,
    val monthDisplay: CalendarMonthDisplay = CalendarMonthDisplay.Titles,
    val sort: CalendarSort = CalendarSort.All,
    val refinement: CalendarRefinement = CalendarRefinement(),
    val firstDayOfWeek: Int = CalendarDay.MONDAY,
    val density: CalendarDensity = CalendarDensity.Comfortable,
    /** A row of upcoming Library releases on Home. */
    val showLibraryRowOnHome: Boolean = false,
    /** Calendar entry in the sidebar / top bar / bottom bar. */
    val showInNavigation: Boolean = true,
) {
    /** The scope actually shown: a hidden Global calendar always falls back to the Library one. */
    val effectiveScope: CalendarScope
        get() = if (globalCalendarEnabled) scope else CalendarScope.Personal
}

/** Hiding the Calendar from navigation keeps it reachable through the Home row. */
internal fun CalendarSettingsUiState.withShowInNavigation(show: Boolean): CalendarSettingsUiState =
    if (show) copy(showInNavigation = true) else copy(showInNavigation = false, showLibraryRowOnHome = true)

/** Removing the Home row while the Calendar is hidden brings its navigation entry back. */
internal fun CalendarSettingsUiState.withLibraryRowOnHome(show: Boolean): CalendarSettingsUiState =
    if (show) copy(showLibraryRowOnHome = true) else copy(showLibraryRowOnHome = false, showInNavigation = true)

internal object CalendarSettingsRepository {
    private val _uiState = MutableStateFlow(CalendarSettingsUiState())
    val uiState: StateFlow<CalendarSettingsUiState> = _uiState.asStateFlow()

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
        _uiState.value = CalendarSettingsUiState()
    }

    fun setGlobalCalendarEnabled(enabled: Boolean) = update { it.copy(globalCalendarEnabled = enabled) }

    fun setViewMode(viewMode: CalendarViewMode) = update { it.copy(viewMode = viewMode) }

    fun setScope(scope: CalendarScope) = update { it.copy(scope = scope) }

    fun setContentFilter(filter: CalendarContentFilter) = update { it.copy(contentFilter = filter) }

    fun setMonthDisplay(display: CalendarMonthDisplay) = update { it.copy(monthDisplay = display) }

    fun setSort(sort: CalendarSort) = update { it.copy(sort = sort) }

    fun setFirstDayOfWeek(day: Int) {
        if (day in CalendarWeekStartOptions) update { it.copy(firstDayOfWeek = day) }
    }

    fun setDensity(density: CalendarDensity) = update { it.copy(density = density) }

    /**
     * The Calendar must stay reachable: hiding it from navigation turns the Home row on, and
     * turning the Home row off while it is hidden brings the navigation entry back.
     */
    fun setShowInNavigation(show: Boolean) = update { it.withShowInNavigation(show) }

    fun setShowLibraryRowOnHome(show: Boolean) = update { it.withLibraryRowOnHome(show) }

    fun setRefinement(refinement: CalendarRefinement) = update { it.copy(refinement = refinement) }

    private inline fun update(transform: (CalendarSettingsUiState) -> CalendarSettingsUiState) {
        ensureLoaded()
        val next = transform(_uiState.value)
        if (next == _uiState.value) return
        _uiState.value = next
        CalendarSettingsStorage.savePayload(encodeCalendarSettings(next))
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = decodeCalendarSettings(CalendarSettingsStorage.loadPayload())
    }
}

internal fun encodeCalendarSettings(state: CalendarSettingsUiState): String =
    CalendarSettingsJson.encodeToString(
        StoredCalendarSettings.serializer(),
        StoredCalendarSettings(
            globalCalendarEnabled = state.globalCalendarEnabled,
            viewMode = state.viewMode.name,
            scope = state.scope.name,
            contentFilter = state.contentFilter.name,
            monthDisplay = state.monthDisplay.name,
            sort = state.sort.name,
            genres = state.refinement.genres.toList(),
            languages = state.refinement.languages.toList(),
            countries = state.refinement.countries.toList(),
            services = state.refinement.services.toList(),
            firstDayOfWeek = state.firstDayOfWeek,
            density = state.density.name,
            showLibraryRowOnHome = state.showLibraryRowOnHome,
            showInNavigation = state.showInNavigation,
        ),
    )

internal fun decodeCalendarSettings(payload: String?): CalendarSettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { CalendarSettingsJson.decodeFromString(StoredCalendarSettings.serializer(), it) }.getOrNull() }
        ?: return CalendarSettingsUiState()
    return CalendarSettingsUiState(
        globalCalendarEnabled = stored.globalCalendarEnabled,
        viewMode = CalendarViewMode.entries.firstOrNull { it.name == stored.viewMode } ?: CalendarViewMode.Month,
        scope = CalendarScope.entries.firstOrNull { it.name == stored.scope } ?: CalendarScope.Global,
        contentFilter = CalendarContentFilter.entries.firstOrNull { it.name == stored.contentFilter }
            ?: CalendarContentFilter.All,
        monthDisplay = CalendarMonthDisplay.entries.firstOrNull { it.name == stored.monthDisplay }
            ?: CalendarMonthDisplay.Titles,
        // "Popularity" was the name of today's default order before "All" / "New" existed.
        sort = CalendarSort.entries.firstOrNull { it.name == stored.sort } ?: CalendarSort.All,
        refinement = CalendarRefinement(
            genres = stored.genres.toSet(),
            languages = stored.languages.toSet(),
            countries = stored.countries.toSet(),
            services = stored.services.toSet(),
        ),
        firstDayOfWeek = stored.firstDayOfWeek.takeIf { it in CalendarWeekStartOptions } ?: CalendarDay.MONDAY,
        density = CalendarDensity.entries.firstOrNull { it.name == stored.density } ?: CalendarDensity.Comfortable,
        showLibraryRowOnHome = stored.showLibraryRowOnHome || !stored.showInNavigation,
        showInNavigation = stored.showInNavigation,
    )
}

private val CalendarSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class StoredCalendarSettings(
    @SerialName("global_calendar_enabled") val globalCalendarEnabled: Boolean = true,
    @SerialName("view_mode") val viewMode: String = "Month",
    @SerialName("scope") val scope: String = "Global",
    @SerialName("content_filter") val contentFilter: String = "All",
    @SerialName("month_display") val monthDisplay: String = "Titles",
    @SerialName("sort") val sort: String = "All",
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("languages") val languages: List<String> = emptyList(),
    @SerialName("countries") val countries: List<String> = emptyList(),
    @SerialName("services") val services: List<String> = emptyList(),
    @SerialName("first_day_of_week") val firstDayOfWeek: Int = CalendarDay.MONDAY,
    @SerialName("density") val density: String = "Comfortable",
    @SerialName("show_library_row_on_home") val showLibraryRowOnHome: Boolean = false,
    @SerialName("show_in_navigation") val showInNavigation: Boolean = true,
)
