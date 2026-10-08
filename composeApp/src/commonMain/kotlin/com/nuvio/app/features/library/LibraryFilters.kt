package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.calendar.CalendarStreamingService
import com.nuvio.app.features.calendar.FacetSection
import com.nuvio.app.features.calendar.FilterChip
import com.nuvio.app.features.calendar.FilterSection
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.calendar.countryDisplayName
import com.nuvio.app.features.calendar.languageDisplayName
import com.nuvio.app.features.calendar.toggle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_close
import nuvio.composeapp.generated.resources.calendar_filter_country
import nuvio.composeapp.generated.resources.calendar_filter_genre
import nuvio.composeapp.generated.resources.calendar_filter_language
import nuvio.composeapp.generated.resources.calendar_filter_services
import nuvio.composeapp.generated.resources.calendar_filters_done
import nuvio.composeapp.generated.resources.calendar_filters_empty
import nuvio.composeapp.generated.resources.calendar_filters_reset
import nuvio.composeapp.generated.resources.calendar_filters_title
import nuvio.composeapp.generated.resources.calendar_services_all
import nuvio.composeapp.generated.resources.calendar_services_others
import nuvio.composeapp.generated.resources.calendar_sort_by
import nuvio.composeapp.generated.resources.library_filter_decade
import nuvio.composeapp.generated.resources.library_filter_year
import org.jetbrains.compose.resources.stringResource

/** Filter values present in the Library, most common first, with counts. */
internal data class LibraryFilterOptions(
    val services: List<Pair<CalendarStreamingService, Int>> = emptyList(),
    val othersCount: Int = 0,
    val genres: List<Pair<String, Int>> = emptyList(),
    val decades: List<Pair<Int, Int>> = emptyList(),
    val countries: List<Pair<String, Int>> = emptyList(),
    val languages: List<Pair<String, Int>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && decades.isEmpty() && countries.isEmpty() && languages.isEmpty()

    companion object {
        fun from(items: List<LibraryItem>, info: (LibraryItem) -> LibraryTitleInfo?): LibraryFilterOptions {
            val facets = items.map { item -> item.libraryFacets(info(item)) }
            fun count(values: List<String>) = values.groupingBy { it }.eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
            return LibraryFilterOptions(
                services = CalendarStreamingService.entries.map { service -> service to facets.count { service in it.services } },
                othersCount = facets.count { it.services.isEmpty() },
                genres = count(facets.flatMap { it.genres }),
                decades = items.mapNotNull { item -> item.releaseYear(info(item))?.let(::decadeOf) }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.key }
                    .map { it.key to it.value },
                countries = count(facets.flatMap { it.countries }),
                languages = count(facets.mapNotNull { it.language }),
            )
        }
    }
}

/** Sort order inside the dialog: New, Trending, Rating first, as on the Calendar. */
internal fun librarySortDialogOrder(available: List<LibrarySortOption>): List<LibrarySortOption> {
    val leading = listOf(LibrarySortOption.NEW, LibrarySortOption.TRENDING, LibrarySortOption.RATING)
    return leading.filter { it in available } + available.filterNot { it in leading }
}

@Composable
internal fun LibraryFiltersDialog(
    sortOption: LibrarySortOption,
    sortOptions: List<LibrarySortOption>,
    refinement: LibraryRefinement,
    options: LibraryFilterOptions,
    sortLabel: @Composable (LibrarySortOption) -> String,
    onSortChange: (LibrarySortOption) -> Unit,
    onRefinementChange: (LibraryRefinement) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val calendar = refinement.calendar
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(NuvioTokens.Space.s16)
                .widthIn(max = 600.dp)
                .heightIn(max = 720.dp)
                .fillMaxWidth(),
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            shape = tokens.shapes.dialog,
        ) {
            Column(modifier = Modifier.padding(NuvioTokens.Space.s20)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.calendar_filters_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.calendar_close))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s18),
                ) {
                    FilterSection(stringResource(Res.string.calendar_sort_by)) {
                        librarySortDialogOrder(sortOptions).forEach { option ->
                            FilterChip(
                                label = sortLabel(option),
                                selected = sortOption == option,
                                onClick = { onSortChange(option) },
                            )
                        }
                    }

                    FilterSection(stringResource(Res.string.calendar_filter_services)) {
                        FilterChip(
                            label = stringResource(Res.string.calendar_services_all),
                            selected = calendar.services.isEmpty(),
                            onClick = { onRefinementChange(refinement.copy(calendar = calendar.copy(services = emptySet()))) },
                        )
                        options.services.forEach { (service, count) ->
                            FilterChip(
                                label = if (count > 0) "${service.displayName}  $count" else service.displayName,
                                selected = service.name in calendar.services,
                                onClick = {
                                    onRefinementChange(
                                        refinement.copy(calendar = calendar.copy(services = calendar.services.toggle(service.name))),
                                    )
                                },
                            )
                        }
                        val othersLabel = stringResource(Res.string.calendar_services_others)
                        FilterChip(
                            label = if (options.othersCount > 0) "$othersLabel  ${options.othersCount}" else othersLabel,
                            selected = CalendarStreamingService.OTHERS in calendar.services,
                            onClick = {
                                onRefinementChange(
                                    refinement.copy(
                                        calendar = calendar.copy(services = calendar.services.toggle(CalendarStreamingService.OTHERS)),
                                    ),
                                )
                            },
                        )
                    }

                    if (options.isEmpty) {
                        Text(
                            text = stringResource(Res.string.calendar_filters_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_genre),
                        options = options.genres,
                        selected = calendar.genres,
                        label = { it },
                        onToggle = { onRefinementChange(refinement.copy(calendar = calendar.copy(genres = calendar.genres.toggle(it)))) },
                    )
                    if (options.decades.isNotEmpty() || refinement.decades.isNotEmpty()) {
                        val shown = options.decades + refinement.decades.filter { d -> options.decades.none { it.first == d } }.map { it to 0 }
                        FilterSection(stringResource(Res.string.library_filter_year)) {
                            shown.forEach { (decade, count) ->
                                val label = stringResource(Res.string.library_filter_decade, decade)
                                FilterChip(
                                    label = if (count > 0) "$label  $count" else label,
                                    selected = decade in refinement.decades,
                                    onClick = {
                                        val next = if (decade in refinement.decades) refinement.decades - decade else refinement.decades + decade
                                        onRefinementChange(refinement.copy(decades = next))
                                    },
                                )
                            }
                        }
                    }
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_country),
                        options = options.countries,
                        selected = calendar.countries,
                        label = ::countryDisplayName,
                        onToggle = {
                            onRefinementChange(refinement.copy(calendar = calendar.copy(countries = calendar.countries.toggle(it))))
                        },
                    )
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_language),
                        options = options.languages,
                        selected = calendar.languages,
                        label = ::languageDisplayName,
                        onToggle = {
                            onRefinementChange(refinement.copy(calendar = calendar.copy(languages = calendar.languages.toggle(it))))
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    TextButton(onClick = { onRefinementChange(LibraryRefinement()) }) {
                        Text(stringResource(Res.string.calendar_filters_reset), color = tokens.colors.textSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.calendar_filters_done),
                        modifier = Modifier.widthIn(max = 180.dp),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}
