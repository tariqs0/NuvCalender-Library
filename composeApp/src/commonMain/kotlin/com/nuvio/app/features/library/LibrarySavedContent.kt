package com.nuvio.app.features.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.calendar.CalendarFiltersButton
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_all_types
import nuvio.composeapp.generated.resources.library_filter_list
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_filter_type
import nuvio.composeapp.generated.resources.library_sort_added_asc
import nuvio.composeapp.generated.resources.library_sort_added_desc
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_sort_new
import nuvio.composeapp.generated.resources.library_sort_provider_order
import nuvio.composeapp.generated.resources.library_sort_rating
import nuvio.composeapp.generated.resources.library_sort_trending
import nuvio.composeapp.generated.resources.library_sort_year_asc
import nuvio.composeapp.generated.resources.library_sort_year_desc
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibrarySavedControls(
    layoutMode: LibraryLayoutMode,
    sourceMode: LibrarySourceMode,
    sortOption: LibrarySortOption,
    listOptions: List<NuvioDropdownOption>,
    selectedListKey: String?,
    onListSelected: (String) -> Unit,
    availableTypes: List<String>,
    selectedType: String?,
    onTypeSelected: (String?) -> Unit,
    onSortSelected: (LibrarySortOption) -> Unit,
    activeFilters: Int,
    onFiltersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortOptions = availableLibrarySortOptions(sourceMode)
    val allTypesLabel = stringResource(Res.string.library_filter_all_types)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layoutMode == LibraryLayoutMode.VERTICAL && listOptions.isNotEmpty()) {
            val selected = listOptions.firstOrNull { it.key == selectedListKey } ?: listOptions.first()
            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_list),
                label = selected.label,
                selectedKey = selected.key,
                options = listOptions,
                enabled = listOptions.size > 1,
                onSelected = { option -> onListSelected(option.key) },
            )
        }

        // Content type (movies, series, anime and addon categories) applies to every list.
        val typeOptions = buildList {
            add(NuvioDropdownOption(key = "", label = allTypesLabel))
            addAll(availableTypes.map { type -> NuvioDropdownOption(key = type, label = localizedMediaTypeLabel(type)) })
        }
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_type),
            label = selectedType?.let(::localizedMediaTypeLabel) ?: allTypesLabel,
            selectedKey = selectedType.orEmpty(),
            options = typeOptions,
            enabled = typeOptions.size > 1,
            onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
        )

        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_sort),
            label = librarySortOptionLabel(sortOption),
            selectedKey = sortOption.name,
            options = sortOptions.map { option ->
                NuvioDropdownOption(key = option.name, label = librarySortOptionLabel(option))
            },
            enabled = sortOptions.size > 1,
            onSelected = { option ->
                LibrarySortOption.entries
                    .firstOrNull { it.name == option.key }
                    ?.let(onSortSelected)
            },
        )

        CalendarFiltersButton(activeCount = activeFilters, onClick = onFiltersClick)
    }
}

internal fun LazyListScope.libraryVerticalContent(
    projection: LibraryVerticalProjection,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
    progressFor: (LibraryItem) -> LibraryProgress? = { null },
    showProgress: Boolean = false,
    onRemove: ((LibraryItem, LibrarySection?) -> Unit)? = null,
    removeFromSection: Boolean = true,
) {
    items(
        items = projection.entries.chunked(columns),
        key = { rowEntries ->
            val firstEntry = rowEntries.first()
            "library-vertical:${firstEntry.item.type}:${firstEntry.item.id}"
        },
    ) { rowEntries ->
        PosterGridRow(
            items = rowEntries.map { entry ->
                if (showProgress) entry.item.previewWithProgress(progressFor(entry.item)) else entry.item.toMetaPreview()
            },
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick?.let { callback ->
                { preview -> rowEntries.findEntry(preview)?.item?.let(callback) }
            },
            onPosterLongClick = onPosterLongClick?.let { callback ->
                { preview ->
                    rowEntries.findEntry(preview)?.let { entry -> callback(entry.item, entry.section) }
                }
            },
            posterOverlay = { preview ->
                val entry = rowEntries.findEntry(preview)
                LibraryPosterOverlay(
                    onRemove = if (entry != null && onRemove != null) {
                        { onRemove(entry.item, entry.section.takeIf { removeFromSection }) }
                    } else {
                        null
                    },
                    progressFraction = entry?.let { progressFor(it.item)?.progressFraction }
                        ?.takeIf { showProgress },
                )
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalSkeletonItems(columns: Int) {
    items(
        count = 2,
        key = { index -> "library-vertical-skeleton:$index" },
    ) {
        PosterGridSkeletonRow(
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
        )
    }
}

internal fun LazyItemScope.libraryContentTransitionModifier(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(durationMillis = 160),
        placementSpec = tween(durationMillis = 180),
        fadeOutSpec = tween(durationMillis = 90),
    )

@Composable
internal fun librarySortLabel(option: LibrarySortOption): String = librarySortOptionLabel(option)

@Composable
private fun librarySortOptionLabel(option: LibrarySortOption): String =
    when (option) {
        LibrarySortOption.DEFAULT -> stringResource(Res.string.library_sort_provider_order)
        LibrarySortOption.ADDED_DESC -> stringResource(Res.string.library_sort_added_desc)
        LibrarySortOption.ADDED_ASC -> stringResource(Res.string.library_sort_added_asc)
        LibrarySortOption.TITLE_ASC -> stringResource(Res.string.library_sort_title_asc)
        LibrarySortOption.TITLE_DESC -> stringResource(Res.string.library_sort_title_desc)
        LibrarySortOption.YEAR_DESC -> stringResource(Res.string.library_sort_year_desc)
        LibrarySortOption.YEAR_ASC -> stringResource(Res.string.library_sort_year_asc)
        LibrarySortOption.NEW -> stringResource(Res.string.library_sort_new)
        LibrarySortOption.TRENDING -> stringResource(Res.string.library_sort_trending)
        LibrarySortOption.RATING -> stringResource(Res.string.library_sort_rating)
    }

private fun List<LibraryVerticalEntry>.findEntry(preview: MetaPreview): LibraryVerticalEntry? =
    firstOrNull { entry -> entry.item.id == preview.id && entry.item.type == preview.type }
