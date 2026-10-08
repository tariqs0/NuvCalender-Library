package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.nuvio.app.core.ui.ScreenActivityEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.calendar.CalendarDay
import com.nuvio.app.features.calendar.CalendarRepository
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_list_continue_watching
import nuvio.composeapp.generated.resources.library_list_watched
import nuvio.composeapp.generated.resources.library_list_watchlist
import nuvio.composeapp.generated.resources.library_progress_episode
import nuvio.composeapp.generated.resources.library_progress_next
import nuvio.composeapp.generated.resources.library_progress_percent
import nuvio.composeapp.generated.resources.library_remove_poster
import org.jetbrains.compose.resources.stringResource

/** Section keys for the automatic lists, so they can sit beside provider lists. */
internal const val SmartListKeyPrefix = "smart:"

internal fun LibrarySmartList.sectionKey(): String = SmartListKeyPrefix + name

internal fun smartListForKey(key: String?): LibrarySmartList? =
    key?.takeIf { it.startsWith(SmartListKeyPrefix) }
        ?.removePrefix(SmartListKeyPrefix)
        ?.let { name -> LibrarySmartList.entries.firstOrNull { it.name == name } }

@Composable
internal fun LibrarySmartList.title(): String = stringResource(
    when (this) {
        LibrarySmartList.ContinueWatching -> Res.string.library_list_continue_watching
        LibrarySmartList.Watchlist -> Res.string.library_list_watchlist
        LibrarySmartList.Watched -> Res.string.library_list_watched
    },
)

/**
 * Everything the Library page shows after hiding, type and filter selection: the provider lists
 * plus the automatic Continue Watching / Watchlist / Watched lists with each title's progress.
 */
internal data class LibraryOrganizedContent(
    val visibleSections: List<LibrarySection>,
    val smartLists: Map<LibrarySmartList, List<LibraryItem>>,
    val progress: Map<String, LibraryProgress>,
    val availableTypes: List<String>,
    val filterOptions: LibraryFilterOptions,
    val sortContext: LibrarySortContext,
) {
    fun progressFor(item: LibraryItem): LibraryProgress? = progress[libraryDisplayItemKey(item)]

    /** The provider list a title is in (for list-specific removal and the poster actions). */
    fun sourceSectionFor(item: LibraryItem): LibrarySection? =
        visibleSections.firstOrNull { section -> section.items.any { libraryDisplayItemKey(it) == libraryDisplayItemKey(item) } }
}

internal fun organizeLibrary(
    sections: List<LibrarySection>,
    hidden: LibraryHiddenUiState,
    selectedType: String?,
    refinement: LibraryRefinement,
    sortOption: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    titleInfo: Map<String, LibraryTitleInfo>,
    watchedItems: List<com.nuvio.app.features.watched.WatchedItem>,
    progressEntries: List<com.nuvio.app.features.watchprogress.WatchProgressEntry>,
    fullyWatchedSeriesKeys: Set<String>,
    todayEpochDay: Long,
): LibraryOrganizedContent {
    val sortContext = LibrarySortContext(titleInfo = titleInfo, todayEpochDay = todayEpochDay)
    fun info(item: LibraryItem) = titleInfo[libraryDisplayItemKey(item)]

    // Hidden titles never appear in the regular lists.
    val unhidden = sections.map { section ->
        section.copy(items = section.items.filterNot { hidden.isHidden(it.type, it.id) })
    }
    val allItems = unhidden.flatMap { it.items }.distinctBy(::libraryDisplayItemKey)
    val availableTypes = allItems.map { it.libraryContentType() }.filter { it.isNotBlank() }.distinct().sorted()
    val effectiveType = selectedType?.trim()?.lowercase()?.takeIf { it in availableTypes }
    fun accepted(item: LibraryItem) =
        (effectiveType == null || item.libraryContentType() == effectiveType) && refinement.matches(item, info(item))

    val visibleSections = unhidden.map { section -> section.copy(items = section.items.filter(::accepted)) }
    val visibleItems = allItems.filter(::accepted)

    val progress = visibleItems.associate { item ->
        libraryDisplayItemKey(item) to classifyLibraryItem(
            item = item,
            watchedItems = watchedItems.filter { it.belongsTo(item) },
            progressEntries = progressEntries.filter { it.belongsTo(item) },
            fullyWatchedSeries = com.nuvio.app.features.watched.watchedItemKeys(type = item.type, id = item.id)
                .any(fullyWatchedSeriesKeys::contains),
            info = info(item),
            todayEpochDay = todayEpochDay,
        )
    }
    val byList = visibleItems.groupBy { progress.getValue(libraryDisplayItemKey(it)).list }
    val smartLists = LibrarySmartList.entries.associateWith { list ->
        val items = byList[list].orEmpty()
        if (list == LibrarySmartList.ContinueWatching) {
            // Continue Watching follows what was watched most recently.
            items.sortedByDescending { progress.getValue(libraryDisplayItemKey(it)).lastActivityEpochMs }
        } else {
            sortLibraryItems(items, sortOption, sourceMode, context = sortContext)
        }
    }
    return LibraryOrganizedContent(
        visibleSections = visibleSections,
        smartLists = smartLists,
        progress = progress,
        availableTypes = availableTypes,
        filterOptions = LibraryFilterOptions.from(allItems, ::info),
        sortContext = sortContext,
    )
}

/** Collects the synced data the organizer needs and keeps the Library calendar data loading. */
@Composable
internal fun rememberLibraryOrganizedContent(
    sections: List<LibrarySection>,
    selectedType: String?,
    refinement: LibraryRefinement,
    sortOption: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    fullyWatchedSeriesKeys: Set<String>,
): Pair<LibraryOrganizedContent, LibraryHiddenUiState> {
    var today by remember { mutableStateOf(CalendarDay.today().epochDay) }
    // While the Library is on screen: pick up new episodes / seasons (re-read from the addons once
    // the data is stale) and roll "today" over, so a series moves back as soon as an episode airs.
    ScreenActivityEffect { active ->
        while (active) {
            CalendarRepository.ensurePersonalLoaded()
            WatchProgressRepository.ensureLoaded()
            today = CalendarDay.today().epochDay
            delay(LIBRARY_RECHECK_INTERVAL_MS)
        }
    }
    val hidden by remember {
        LibraryHiddenRepository.ensureLoaded()
        LibraryHiddenRepository.uiState
    }.collectAsStateWithLifecycle()
    val titleInfo by CalendarRepository.libraryTitleInfo.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val progressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val content = remember(
        sections, hidden, selectedType, refinement, sortOption, sourceMode, titleInfo,
        watchedUiState.items, progressUiState.entries, fullyWatchedSeriesKeys, today,
    ) {
        organizeLibrary(
            sections = sections,
            hidden = hidden,
            selectedType = selectedType,
            refinement = refinement,
            sortOption = sortOption,
            sourceMode = sourceMode,
            titleInfo = titleInfo,
            watchedItems = watchedUiState.items,
            progressEntries = progressUiState.entries,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            todayEpochDay = today,
        )
    }
    return content to hidden
}

/** Detail line under a poster: the last watched episode and progress, or the next episode. */
@Composable
internal fun LibraryProgress.detailLine(): String? {
    val percent = progressFraction?.takeIf { it > 0f }?.let { (it * 100).toInt().coerceIn(1, 99) }
    val episode = if (lastSeason != null && lastEpisode != null) {
        stringResource(Res.string.library_progress_episode, lastSeason, lastEpisode)
    } else {
        null
    }
    return when {
        episode != null && percent != null -> "$episode · " + stringResource(Res.string.library_progress_percent, percent)
        percent != null -> stringResource(Res.string.library_progress_percent, percent)
        nextSeason != null && nextEpisode != null -> stringResource(Res.string.library_progress_next, nextSeason, nextEpisode)
        episode != null -> episode
        else -> null
    }
}

/** A poster preview whose detail line carries the progress text instead of the release date. */
@Composable
internal fun LibraryItem.previewWithProgress(progress: LibraryProgress?): MetaPreview {
    val preview = toMetaPreview()
    val line = progress?.takeIf { it.list == LibrarySmartList.ContinueWatching }?.detailLine() ?: return preview
    return preview.copy(releaseInfo = line)
}

/**
 * Poster overlay for the Library: an "×" in the top-left corner removes the title in one click,
 * and in-progress titles get a progress bar along the bottom edge.
 */
@Composable
internal fun BoxScope.LibraryPosterOverlay(
    onRemove: (() -> Unit)?,
    progressFraction: Float?,
) {
    val tokens = MaterialTheme.nuvio
    progressFraction?.takeIf { it > 0f }?.let { fraction ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(tokens.colors.accent),
            )
        }
    }
    if (onRemove != null) {
        val label = stringResource(Res.string.library_remove_poster)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onRemove)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The Hidden list as a poster grid; "×" moves a title back to the regular lists. */
internal fun LazyListScope.libraryHiddenGrid(
    items: List<HiddenLibraryItem>,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    items(
        items = items.sortedByDescending { it.hiddenAtEpochMs }.chunked(columns),
        key = { row -> "library-hidden:${row.first().key}" },
    ) { row ->
        PosterGridRow(
            items = row.map { it.toMetaPreview() },
            columns = columns,
            modifier = libraryContentTransitionModifier().padding(horizontal = 16.dp),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
            posterOverlay = { preview ->
                LibraryPosterOverlay(
                    onRemove = { LibraryHiddenRepository.unhide(preview.type, preview.id) },
                    progressFraction = null,
                )
            },
        )
    }
}

/** How often an open Library re-checks for new releases and a new day. */
private const val LIBRARY_RECHECK_INTERVAL_MS: Long = 15L * 60 * 1000
