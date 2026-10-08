package com.nuvio.app.features.calendar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.calendar_empty_filtered
import nuvio.composeapp.generated.resources.calendar_filter_anime
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterHoverPreview
import com.nuvio.app.features.library.LibraryRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_display_posters
import nuvio.composeapp.generated.resources.calendar_display_titles
import nuvio.composeapp.generated.resources.calendar_empty_day
import nuvio.composeapp.generated.resources.calendar_empty_library
import nuvio.composeapp.generated.resources.calendar_empty_range_global
import nuvio.composeapp.generated.resources.calendar_empty_range_personal
import nuvio.composeapp.generated.resources.calendar_error_global
import nuvio.composeapp.generated.resources.calendar_filter_all
import nuvio.composeapp.generated.resources.calendar_filter_movies
import nuvio.composeapp.generated.resources.calendar_filter_series
import nuvio.composeapp.generated.resources.calendar_loading
import nuvio.composeapp.generated.resources.calendar_more_count
import nuvio.composeapp.generated.resources.calendar_next
import nuvio.composeapp.generated.resources.calendar_previous
import nuvio.composeapp.generated.resources.calendar_refresh
import nuvio.composeapp.generated.resources.calendar_retry
import nuvio.composeapp.generated.resources.calendar_scope_global
import nuvio.composeapp.generated.resources.calendar_scope_personal
import nuvio.composeapp.generated.resources.calendar_source
import nuvio.composeapp.generated.resources.calendar_title
import nuvio.composeapp.generated.resources.calendar_today
import nuvio.composeapp.generated.resources.calendar_view_day
import nuvio.composeapp.generated.resources.calendar_view_month
import nuvio.composeapp.generated.resources.calendar_view_week
import org.jetbrains.compose.resources.stringResource

// Sizes per density setting (Settings → Content & Discovery → Calendar).
private val CalendarDensity.monthCellMinHeight: Dp
    get() = when (this) {
        CalendarDensity.Compact -> 96.dp
        CalendarDensity.Comfortable -> 124.dp
        CalendarDensity.Spacious -> 164.dp
    }

private val CalendarDensity.phoneMonthCellHeight: Dp
    get() = when (this) {
        CalendarDensity.Compact -> 60.dp
        CalendarDensity.Comfortable -> 72.dp
        CalendarDensity.Spacious -> 92.dp
    }

private val CalendarDensity.monthChipHeight: Dp
    get() = when (this) {
        CalendarDensity.Compact -> 18.dp
        CalendarDensity.Comfortable -> 22.dp
        CalendarDensity.Spacious -> 26.dp
    }

private fun CalendarDensity.agendaArtWidth(landscape: Boolean, compact: Boolean): Dp = when {
    landscape -> when (this) {
        CalendarDensity.Compact -> 168.dp
        CalendarDensity.Comfortable -> 220.dp
        CalendarDensity.Spacious -> 280.dp
    }
    compact -> when (this) {
        CalendarDensity.Compact -> 52.dp
        CalendarDensity.Comfortable -> 64.dp
        CalendarDensity.Spacious -> 80.dp
    }
    else -> when (this) {
        CalendarDensity.Compact -> 76.dp
        CalendarDensity.Comfortable -> 96.dp
        CalendarDensity.Spacious -> 120.dp
    }
}

private val MonthDayHeaderHeight = 28.dp
private val CompactBreakpoint = 600.dp
private val WideToolbarBreakpoint = 1280.dp
private val WeekColumnsBreakpoint = 760.dp

@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onOpenDetails: ((MetaPreview) -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val settings by remember {
        CalendarSettingsRepository.ensureLoaded()
        CalendarSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val calendarScope = settings.effectiveScope
    val viewMode = settings.viewMode
    val today = remember { CalendarDay.today() }
    var focusEpochDay by rememberSaveable { mutableLongStateOf(today.epochDay) }
    val focus = CalendarDay.ofEpochDay(focusEpochDay)
    val firstDayOfWeek = settings.firstDayOfWeek
    val range = remember(viewMode, focusEpochDay, firstDayOfWeek) { viewMode.visibleRange(focus, firstDayOfWeek) }

    val globalState by CalendarRepository.globalState.collectAsStateWithLifecycle()
    val personalState by CalendarRepository.personalState.collectAsStateWithLifecycle()
    val libraryState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val contentFilter = settings.contentFilter
    // Both calendars carry streaming-service data, so one set of filters applies to either.
    val refinement = settings.refinement
    val sort = settings.sort
    val sourceFeed = if (calendarScope == CalendarScope.Global) globalState else personalState
    val feed = remember(sourceFeed, contentFilter, refinement) { sourceFeed.filtered(contentFilter, refinement) }
    var showFilters by remember { mutableStateOf(false) }
    val isWatched = rememberCalendarWatchedResolver()
    var selectedEntry by remember { mutableStateOf<CalendarEntry?>(null) }
    val bottomPadding = LocalNuvioBottomNavigationOverlayPadding.current + NuvioTokens.Space.s24

    LaunchedEffect(calendarScope, range) {
        when (calendarScope) {
            CalendarScope.Global -> CalendarRepository.ensureGlobalRange(range)
            CalendarScope.Personal -> CalendarRepository.ensurePersonalLoaded()
        }
    }

    val openDay: (CalendarDay) -> Unit = { day ->
        focusEpochDay = day.epochDay
        CalendarSettingsRepository.setViewMode(CalendarViewMode.Day)
    }
    val entryCallbacks = CalendarEntryCallbacks(
        sort = sort,
        density = settings.density,
        isWatched = isWatched,
        onEntryClick = { selectedEntry = it },
        onOpenDetails = onOpenDetails,
    )
    val entriesInRange = remember(feed, range) { feed.entriesIn(range) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .padding(top = if (topChromePadding == null) tokens.spacing.screenTop else 0.dp),
    ) {
        NuvioScreenHeader(
            title = stringResource(Res.string.calendar_title),
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16),
            topPadding = topChromePadding,
            actions = {
                // Sits between the shared header's fullscreen ("size") action and Refresh.
                if (viewMode == CalendarViewMode.Month) {
                    val showingPosters = settings.monthDisplay == CalendarMonthDisplay.Posters
                    IconButton(
                        onClick = {
                            CalendarSettingsRepository.setMonthDisplay(
                                if (showingPosters) CalendarMonthDisplay.Titles else CalendarMonthDisplay.Posters,
                            )
                        },
                    ) {
                        Icon(
                            imageVector = if (showingPosters) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                            contentDescription = stringResource(
                                if (showingPosters) Res.string.calendar_display_titles else Res.string.calendar_display_posters,
                            ),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        when (calendarScope) {
                            CalendarScope.Global -> CalendarRepository.refreshGlobal(range)
                            CalendarScope.Personal -> CalendarRepository.refreshPersonal()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(Res.string.calendar_refresh),
                        tint = tokens.colors.textPrimary,
                    )
                }
            },
        )

        CalendarToolbar(
            title = calendarPeriodTitle(viewMode, range, focus),
            viewMode = viewMode,
            calendarScope = calendarScope,
            showScopeSelector = settings.globalCalendarEnabled,
            contentFilter = contentFilter,
            showingToday = today in range,
            onPrevious = { focusEpochDay = viewMode.step(focus, -1).epochDay },
            onNext = { focusEpochDay = viewMode.step(focus, 1).epochDay },
            onToday = { focusEpochDay = today.epochDay },
            onViewModeChange = CalendarSettingsRepository::setViewMode,
            onScopeChange = CalendarSettingsRepository::setScope,
            onContentFilterChange = CalendarSettingsRepository::setContentFilter,
            activeRefinements = refinement.activeCount + if (sort != CalendarSort.All) 1 else 0,
            onFiltersClick = { showFilters = true },
        )

        Box(modifier = Modifier.height(NuvioTokens.Space.s3).fillMaxWidth()) {
            if (feed.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = tokens.colors.accent,
                    trackColor = Color.Transparent,
                )
            }
        }

        val statusMessage = calendarStatusMessage(
            calendarScope = calendarScope,
            viewMode = viewMode,
            feed = feed,
            hasEntries = entriesInRange.isNotEmpty(),
            refined = refinement.activeCount > 0 || contentFilter != CalendarContentFilter.All,
            libraryEmpty = libraryState.isLoaded && libraryState.items.isEmpty(),
        )
        if (statusMessage != null && viewMode != CalendarViewMode.Day) {
            CalendarStatusBanner(
                message = statusMessage,
                onRetry = if (calendarScope == CalendarScope.Global && feed.errorMessage != null) {
                    { CalendarRepository.refreshGlobal(range) }
                } else {
                    null
                },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (viewMode) {
                CalendarViewMode.Month -> CalendarMonthView(
                    range = range,
                    focus = focus,
                    today = today,
                    feed = feed,
                    display = settings.monthDisplay,
                    callbacks = entryCallbacks,
                    onDayClick = openDay,
                    bottomPadding = bottomPadding,
                )
                CalendarViewMode.Week -> CalendarWeekView(
                    range = range,
                    today = today,
                    feed = feed,
                    callbacks = entryCallbacks,
                    onDayClick = openDay,
                    bottomPadding = bottomPadding,
                )
                CalendarViewMode.Day -> CalendarDayView(
                    day = focus,
                    today = today,
                    entries = entriesInRange.sortedForDisplay(sort),
                    emptyMessage = statusMessage,
                    callbacks = entryCallbacks,
                    bottomPadding = bottomPadding,
                )
            }
        }
    }

    if (showFilters) {
        CalendarFiltersDialog(
            sort = sort,
            refinement = refinement,
            options = remember(sourceFeed) { CalendarFilterOptions.from(sourceFeed) },
            showServices = true,
            onSortChange = CalendarSettingsRepository::setSort,
            onRefinementChange = CalendarSettingsRepository::setRefinement,
            onDismiss = { showFilters = false },
        )
    }

    selectedEntry?.let { entry ->
        CalendarEntryDialog(
            entry = entry,
            today = today,
            onDismiss = { selectedEntry = null },
            onViewDetails = { preview -> onOpenDetails?.invoke(preview) },
        )
    }
}

private class CalendarEntryCallbacks(
    val sort: CalendarSort,
    val density: CalendarDensity,
    val isWatched: (CalendarEntry) -> Boolean,
    val onEntryClick: (CalendarEntry) -> Unit,
    val onOpenDetails: ((MetaPreview) -> Unit)?,
)

@Composable
private fun calendarStatusMessage(
    calendarScope: CalendarScope,
    viewMode: CalendarViewMode,
    feed: CalendarFeedState,
    hasEntries: Boolean,
    refined: Boolean,
    libraryEmpty: Boolean,
): String? = when {
    hasEntries -> null
    calendarScope == CalendarScope.Personal && libraryEmpty -> stringResource(Res.string.calendar_empty_library)
    refined && feed.hasLoaded && !feed.isLoading -> stringResource(Res.string.calendar_empty_filtered)
    feed.isLoading || !feed.hasLoaded -> stringResource(Res.string.calendar_loading)
    calendarScope == CalendarScope.Global && feed.errorMessage != null -> stringResource(Res.string.calendar_error_global)
    viewMode == CalendarViewMode.Day -> stringResource(Res.string.calendar_empty_day)
    calendarScope == CalendarScope.Global -> stringResource(Res.string.calendar_empty_range_global)
    else -> stringResource(Res.string.calendar_empty_range_personal)
}

// ── Toolbar ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalendarToolbar(
    title: String,
    viewMode: CalendarViewMode,
    calendarScope: CalendarScope,
    showScopeSelector: Boolean,
    contentFilter: CalendarContentFilter,
    showingToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onScopeChange: (CalendarScope) -> Unit,
    onContentFilterChange: (CalendarContentFilter) -> Unit,
    activeRefinements: Int,
    onFiltersClick: () -> Unit,
) {
    val scopeOptions = listOf(
        CalendarScope.Global to stringResource(Res.string.calendar_scope_global),
        CalendarScope.Personal to stringResource(Res.string.calendar_scope_personal),
    )
    val filterOptions = listOf(
        CalendarContentFilter.All to stringResource(Res.string.calendar_filter_all),
        CalendarContentFilter.Movies to stringResource(Res.string.calendar_filter_movies),
        CalendarContentFilter.Series to stringResource(Res.string.calendar_filter_series),
        CalendarContentFilter.Anime to stringResource(Res.string.calendar_filter_anime),
    )
    val viewOptions = listOf(
        CalendarViewMode.Month to stringResource(Res.string.calendar_view_month),
        CalendarViewMode.Week to stringResource(Res.string.calendar_view_week),
        CalendarViewMode.Day to stringResource(Res.string.calendar_view_day),
    )
    val navigation: @Composable (Modifier) -> Unit = { modifier ->
        CalendarPeriodNavigation(
            title = title,
            showingToday = showingToday,
            onPrevious = onPrevious,
            onNext = onNext,
            onToday = onToday,
            modifier = modifier,
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    ) {
        val toolbarWidth = maxWidth
        if (toolbarWidth >= WideToolbarBreakpoint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                navigation(Modifier.weight(1f))
                CalendarSegmentedControl(filterOptions, contentFilter, onContentFilterChange)
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                CalendarFiltersButton(activeRefinements, onFiltersClick)
                Spacer(Modifier.width(NuvioTokens.Space.s12))
                if (showScopeSelector) {
                    CalendarSegmentedControl(scopeOptions, calendarScope, onScopeChange)
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                }
                CalendarSegmentedControl(viewOptions, viewMode, onViewModeChange)
            }
        } else {
            // Date, type filter and Filters always share a row. Without the scope switch (Global
            // calendar turned off) Month / Week / Day joins that row too, so the toolbar is one line.
            val dateRow: @Composable () -> Unit = {
                if (toolbarWidth >= WeekColumnsBreakpoint) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navigation(Modifier.weight(1f))
                        CalendarSegmentedControl(filterOptions, contentFilter, onContentFilterChange)
                        CalendarFiltersButton(activeRefinements, onFiltersClick)
                        if (!showScopeSelector) CalendarSegmentedControl(viewOptions, viewMode, onViewModeChange)
                    }
                } else {
                    // Phones: the same single row, scrolled sideways when it is wider than the screen.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navigation(Modifier.widthIn(max = 280.dp))
                        CalendarSegmentedControl(filterOptions, contentFilter, onContentFilterChange)
                        CalendarFiltersButton(activeRefinements, onFiltersClick)
                        if (!showScopeSelector) CalendarSegmentedControl(viewOptions, viewMode, onViewModeChange)
                    }
                }
            }
            if (showScopeSelector) {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        CalendarSegmentedControl(scopeOptions, calendarScope, onScopeChange, Modifier.weight(1.2f), stretch = true)
                        CalendarSegmentedControl(viewOptions, viewMode, onViewModeChange, Modifier.weight(1f), stretch = true)
                    }
                    dateRow()
                }
            } else {
                dateRow()
            }
        }
    }
}

@Composable
private fun CalendarPeriodNavigation(
    title: String,
    showingToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.calendar_previous),
                tint = tokens.colors.textPrimary,
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.calendar_next),
                tint = tokens.colors.textPrimary,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = NuvioTokens.Space.s8),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(
            onClick = onToday,
            enabled = !showingToday,
            shape = tokens.shapes.button,
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s4),
            modifier = Modifier.height(NuvioTokens.Space.s36),
        ) {
            Text(
                text = stringResource(Res.string.calendar_today),
                style = MaterialTheme.typography.labelLarge,
                color = if (showingToday) tokens.colors.textDisabled else tokens.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun <T> CalendarSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier,
        color = tokens.colors.surfaceCard,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(modifier = Modifier.padding(NuvioTokens.Space.s3)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    modifier = if (stretch) Modifier.weight(1f) else Modifier,
                    color = if (isSelected) tokens.colors.accent else Color.Transparent,
                    contentColor = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s7),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarStatusBanner(message: String, onRetry: (() -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.calendar_retry), color = tokens.colors.accent)
            }
        }
    }
}

// ── Month ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalendarMonthView(
    range: CalendarRange,
    focus: CalendarDay,
    today: CalendarDay,
    feed: CalendarFeedState,
    display: CalendarMonthDisplay,
    callbacks: CalendarEntryCallbacks,
    onDayClick: (CalendarDay) -> Unit,
    bottomPadding: Dp,
) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < CompactBreakpoint
        val weekdayHeaderHeight = NuvioTokens.Space.s32
        val cellHeight = if (compact) {
            callbacks.density.phoneMonthCellHeight
        } else {
            max(callbacks.density.monthCellMinHeight, (maxHeight - weekdayHeaderHeight - bottomPadding) / 6)
        }
        val days = range.days

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NuvioTokens.Space.s16)
                .padding(bottom = bottomPadding),
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(weekdayHeaderHeight)) {
                days.take(7).forEach { day ->
                    Text(
                        text = weekdayShortName(day.isoDayOfWeek),
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .clip(tokens.shapes.card)
                    .border(NuvioTokens.Space.s1, tokens.colors.borderSubtle, tokens.shapes.card),
            ) {
                days.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth().height(cellHeight)) {
                        week.forEach { day ->
                            CalendarMonthCell(
                                day = day,
                                inFocusMonth = day.isSameMonth(focus),
                                today = today,
                                entries = feed.entriesFor(day).sortedForDisplay(callbacks.sort),
                                display = display,
                                compact = compact,
                                cellHeight = cellHeight,
                                callbacks = callbacks,
                                onDayClick = onDayClick,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarMonthCell(
    day: CalendarDay,
    inFocusMonth: Boolean,
    today: CalendarDay,
    entries: List<CalendarEntry>,
    display: CalendarMonthDisplay,
    compact: Boolean,
    cellHeight: Dp,
    callbacks: CalendarEntryCallbacks,
    onDayClick: (CalendarDay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val isToday = day == today
    Column(
        modifier = modifier
            .border(NuvioTokens.Space.s1 / 2, tokens.colors.borderSubtle)
            .background(if (inFocusMonth) Color.Transparent else tokens.colors.surface.copy(alpha = 0.35f))
            .clickable { onDayClick(day) }
            .padding(NuvioTokens.Space.s4),
    ) {
        Box(
            modifier = Modifier
                .height(MonthDayHeaderHeight - NuvioTokens.Space.s4)
                .fillMaxWidth(),
            contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(NuvioTokens.Space.s24)
                    .background(if (isToday) tokens.colors.accent else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        isToday -> tokens.colors.onAccent
                        !inFocusMonth -> tokens.colors.textDisabled
                        day < today -> tokens.colors.textMuted
                        else -> tokens.colors.textPrimary
                    },
                )
            }
        }

        if (entries.isEmpty()) return@Column

        if (display == CalendarMonthDisplay.Posters) {
            CalendarMonthPosterRow(
                entries = entries,
                callbacks = callbacks,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = NuvioTokens.Space.s2),
            )
            return@Column
        }

        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s4),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s3, Alignment.CenterHorizontally),
            ) {
                entries.distinctBy { it.kind }.take(4).forEach { entry ->
                    Box(Modifier.size(NuvioTokens.Space.s6).background(entry.kind.accentColor(), CircleShape))
                }
            }
            Text(
                text = entries.size.toString(),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        val chipSlots = ((cellHeight - MonthDayHeaderHeight - NuvioTokens.Space.s8) / (callbacks.density.monthChipHeight + NuvioTokens.Space.s2))
            .toInt()
            .coerceAtLeast(1)
        val visible = if (entries.size > chipSlots) entries.take(chipSlots - 1) else entries
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            visible.forEach { entry ->
                CalendarHoverable(entry, callbacks) { hoverModifier ->
                    CalendarMonthChip(
                        entry = entry,
                        height = callbacks.density.monthChipHeight,
                        isWatched = callbacks.isWatched(entry),
                        modifier = hoverModifier,
                        onClick = { callbacks.onEntryClick(entry) },
                    )
                }
            }
            if (visible.size < entries.size) {
                Text(
                    text = stringResource(Res.string.calendar_more_count, entries.size - visible.size),
                    modifier = Modifier
                        .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                        .clickable { onDayClick(day) }
                        .padding(horizontal = NuvioTokens.Space.s4),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun CalendarMonthChip(
    entry: CalendarEntry,
    height: Dp,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val accent = entry.kind.accentColor()
    val code = entry.season?.let { season -> entry.episode?.let { "S${season}E$it " } }.orEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .alpha(if (isWatched) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(NuvioTokens.Space.s3).fillMaxHeight().background(accent))
        Text(
            text = code + entry.preview.name,
            modifier = Modifier.weight(1f).padding(horizontal = NuvioTokens.Space.s4),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isWatched) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.padding(end = NuvioTokens.Space.s3).size(NuvioTokens.Icon.xs),
                tint = tokens.colors.accent,
            )
        }
    }
}

// Poster mode: every release of the day as a poster sized to the cell's height, side by side
// in a horizontally scrolling row (mouse wheel scrolls it too while it has room to move).
private val PosterRowGap = 3.dp

/** Largest 2:3 poster that fits the cell; a cell narrower than that poster shrinks it to fit. */
internal fun monthPosterSize(maxWidth: Dp, maxHeight: Dp): DpSize {
    val height = maxHeight.coerceAtLeast(0.dp)
    val width = height * (2f / 3f)
    return if (width <= maxWidth) DpSize(width, height) else DpSize(maxWidth, maxWidth * 1.5f)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CalendarMonthPosterRow(
    entries: List<CalendarEntry>,
    callbacks: CalendarEntryCallbacks,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier) {
        val size = monthPosterSize(maxWidth, maxHeight)
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val delta = change.scrollDelta
                    if (delta.x != 0f || delta.y == 0f) return@onPointerEvent
                    val canMove = if (delta.y > 0) listState.canScrollForward else listState.canScrollBackward
                    if (!canMove) return@onPointerEvent
                    scope.launch { listState.scrollBy(delta.y * PosterWheelStepPx) }
                    event.changes.forEach { it.consume() }
                },
            horizontalArrangement = Arrangement.spacedBy(PosterRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(entries, key = { it.key }) { entry ->
                CalendarHoverable(entry, callbacks) { hoverModifier ->
                    CalendarPosterTile(
                        entry = entry,
                        isWatched = callbacks.isWatched(entry),
                        width = size.width,
                        height = size.height,
                        modifier = hoverModifier,
                        onClick = { callbacks.onEntryClick(entry) },
                    )
                }
            }
        }
        // Edge fade hints that more posters are waiting to the right.
        if (listState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(NuvioTokens.Space.s16)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, tokens.colors.background.copy(alpha = 0.85f)))),
            )
        }
    }
}

private const val PosterWheelStepPx = 48f

@Composable
private fun CalendarPosterTile(
    entry: CalendarEntry,
    isWatched: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val accent = entry.kind.accentColor()
    val posterUrl = entry.preview.poster ?: entry.preview.rawPosterUrl
    var posterFailed by remember(posterUrl) { mutableStateOf(posterUrl.isNullOrBlank()) }
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(accent.copy(alpha = 0.22f))
            .clickable(onClick = onClick),
    ) {
        if (!posterFailed) {
            NuvioAsyncImage(
                model = posterUrl,
                contentDescription = entry.preview.name,
                modifier = Modifier.fillMaxSize().alpha(if (isWatched) 0.55f else 1f),
                contentScale = ContentScale.Crop,
                onError = { posterFailed = true },
            )
        } else {
            // Fallback: the title, so a missing poster never hides the release.
            Text(
                text = entry.preview.name,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(NuvioTokens.Space.s2),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (width < 36.dp) 8.sp else 10.sp,
                    lineHeight = if (width < 36.dp) 9.sp else 12.sp,
                ),
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(NuvioTokens.Space.s2)
                .background(accent),
        )
        if (isWatched && width >= 24.dp) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s2).size(NuvioTokens.Icon.xs),
                tint = tokens.colors.accent,
            )
        }
    }
}

// ── Week ──────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarWeekView(
    range: CalendarRange,
    today: CalendarDay,
    feed: CalendarFeedState,
    callbacks: CalendarEntryCallbacks,
    onDayClick: (CalendarDay) -> Unit,
    bottomPadding: Dp,
) {
    val tokens = MaterialTheme.nuvio
    val dayEntries = remember(feed, range, callbacks.sort) {
        range.days.map { day -> day to feed.entriesFor(day).sortedForDisplay(callbacks.sort) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= WeekColumnsBreakpoint) {
            // Lazy rows of seven cards: every title loads as you scroll, and the dates stay pinned.
            val rowCount = dayEntries.maxOfOrNull { it.second.size } ?: 0
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NuvioTokens.Space.s16,
                    end = NuvioTokens.Space.s16,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                stickyHeader(key = "week-dates") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.colors.background)
                            .padding(vertical = NuvioTokens.Space.s6),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        dayEntries.forEach { (day, _) ->
                            CalendarDayHeading(
                                day = day,
                                today = today,
                                onClick = { onDayClick(day) },
                                stacked = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                items(rowCount, key = { "week-row-$it" }) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        dayEntries.forEach { (_, entries) ->
                            Box(modifier = Modifier.weight(1f)) {
                                entries.getOrNull(rowIndex)?.let { entry ->
                                    CalendarHoverable(entry, callbacks) { hoverModifier ->
                                        CalendarPosterCard(
                                            entry = entry,
                                            isWatched = callbacks.isWatched(entry),
                                            modifier = hoverModifier,
                                            onClick = { callbacks.onEntryClick(entry) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Narrow windows read better as an agenda; each date stays pinned over its releases.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NuvioTokens.Space.s16,
                    end = NuvioTokens.Space.s16,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                dayEntries.forEach { (day, entries) ->
                    stickyHeader(key = "day-${day.epochDay}") {
                        CalendarDayHeading(
                            day = day,
                            today = today,
                            onClick = { onDayClick(day) },
                            stacked = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.colors.background)
                                .padding(vertical = NuvioTokens.Space.s8),
                        )
                    }
                    items(entries, key = { "${day.epochDay}-${it.key}" }) { entry ->
                        CalendarHoverable(entry, callbacks) { hoverModifier ->
                            CalendarAgendaCard(
                                entry = entry,
                                today = today,
                                isWatched = callbacks.isWatched(entry),
                                compact = true,
                                density = callbacks.density,
                                modifier = hoverModifier,
                                onClick = { callbacks.onEntryClick(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayHeading(
    day: CalendarDay,
    today: CalendarDay,
    onClick: () -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val isToday = day == today
    val dayColor = if (isToday) tokens.colors.accent else if (day < today) tokens.colors.textMuted else tokens.colors.textPrimary
    if (stacked) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(if (isToday) tokens.colors.accent.copy(alpha = 0.14f) else tokens.colors.surfaceCard)
                .clickable(onClick = onClick)
                .padding(vertical = NuvioTokens.Space.s8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = weekdayShortName(day.isoDayOfWeek),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = dayColor,
            )
        }
    } else {
        Text(
            text = formatCalendarDayLong(day),
            modifier = modifier.clickable(onClick = onClick),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = dayColor,
        )
    }
}

@Composable
private fun CalendarPosterCard(
    entry: CalendarEntry,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(tokens.shapes.poster)
                .background(tokens.colors.surfaceCard),
        ) {
            NuvioAsyncImage(
                model = entry.preview.poster ?: entry.preview.banner,
                contentDescription = entry.preview.name,
                modifier = Modifier.fillMaxSize().alpha(if (isWatched) 0.6f else 1f),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s3)
                    .background(entry.kind.accentColor()),
            )
            if (isWatched) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s6).size(NuvioTokens.Icon.md),
                    tint = tokens.colors.accent,
                )
            }
        }
        Text(
            text = entry.preview.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = calendarEpisodeLine(entry) ?: stringResource(entry.kind.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Day ───────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalendarDayView(
    day: CalendarDay,
    today: CalendarDay,
    entries: List<CalendarEntry>,
    emptyMessage: String?,
    callbacks: CalendarEntryCallbacks,
    bottomPadding: Dp,
) {
    val tokens = MaterialTheme.nuvio
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(NuvioTokens.Space.s32), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < CompactBreakpoint
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NuvioTokens.Space.s16,
                end = NuvioTokens.Space.s16,
                top = NuvioTokens.Space.s8,
                bottom = bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            items(entries, key = { "${day.epochDay}-${it.key}" }) { entry ->
                CalendarHoverable(entry, callbacks) { hoverModifier ->
                    CalendarAgendaCard(
                        entry = entry,
                        today = today,
                        isWatched = callbacks.isWatched(entry),
                        compact = compact,
                        density = callbacks.density,
                        modifier = hoverModifier,
                        onClick = { callbacks.onEntryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarAgendaCard(
    entry: CalendarEntry,
    today: CalendarDay,
    isWatched: Boolean,
    compact: Boolean,
    density: CalendarDensity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val preview = entry.preview
    val useLandscape = !compact && (entry.thumbnail ?: preview.banner) != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s10),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
    ) {
        Box(
            modifier = Modifier
                .width(density.agendaArtWidth(landscape = useLandscape, compact = compact))
                .aspectRatio(if (useLandscape) 16f / 9f else 2f / 3f)
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.surface),
        ) {
            NuvioAsyncImage(
                model = if (useLandscape) entry.thumbnail ?: preview.banner else preview.poster ?: preview.banner,
                contentDescription = preview.name,
                modifier = Modifier.fillMaxSize().alpha(if (isWatched) 0.6f else 1f),
                contentScale = ContentScale.Crop,
            )
            if (isWatched) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s4).size(NuvioTokens.Icon.md),
                    tint = tokens.colors.accent,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).heightIn(min = NuvioTokens.Space.s48),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            CalendarKindBadge(kind = entry.kind)
            Text(
                text = preview.name,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            calendarEpisodeLine(entry)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                preview.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val sourceText = entry.sourceLabel?.takeIf(String::isNotBlank)
                ?.let { stringResource(Res.string.calendar_source, it) }
            val metaLine = listOfNotNull(
                if (entry.day == today) null else formatCalendarDayLong(entry.day).takeIf { compact },
                sourceText,
            ).joinToString(" · ")
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Shared ────────────────────────────────────────────────────────────────────────────────────

/** Wraps an entry in the same desktop hover preview used by Home and catalog posters. */
@Composable
private fun CalendarHoverable(
    entry: CalendarEntry,
    callbacks: CalendarEntryCallbacks,
    content: @Composable (Modifier) -> Unit,
) {
    val onOpenDetails = callbacks.onOpenDetails
    HomePosterHoverPreview(
        item = entry.preview,
        isWatched = callbacks.isWatched(entry),
        onClick = onOpenDetails?.let { open -> { open(entry.preview) } },
        onLongClick = { callbacks.onEntryClick(entry) },
        content = content,
    )
}
