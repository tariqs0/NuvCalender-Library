package com.nuvio.app.features.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_close
import nuvio.composeapp.generated.resources.calendar_filter_country
import nuvio.composeapp.generated.resources.calendar_filter_genre
import nuvio.composeapp.generated.resources.calendar_filter_language
import nuvio.composeapp.generated.resources.calendar_filter_services
import nuvio.composeapp.generated.resources.calendar_services_all
import nuvio.composeapp.generated.resources.calendar_services_others
import nuvio.composeapp.generated.resources.calendar_filters
import nuvio.composeapp.generated.resources.calendar_filters_done
import nuvio.composeapp.generated.resources.calendar_filters_empty
import nuvio.composeapp.generated.resources.calendar_filters_reset
import nuvio.composeapp.generated.resources.calendar_filters_title
import nuvio.composeapp.generated.resources.calendar_sort_by
import nuvio.composeapp.generated.resources.calendar_sort_all
import nuvio.composeapp.generated.resources.calendar_sort_new
import nuvio.composeapp.generated.resources.calendar_sort_rating
import nuvio.composeapp.generated.resources.calendar_sort_title
import org.jetbrains.compose.resources.stringResource

/** Values present in the loaded Global calendar, most common first, with their counts. */
internal data class CalendarFilterOptions(
    val genres: List<Pair<String, Int>>,
    val languages: List<Pair<String, Int>>,
    val countries: List<Pair<String, Int>>,
    /** Every service with its count (zero included) and the "Others" count. */
    val services: List<Pair<CalendarStreamingService, Int>>,
    val othersCount: Int,
) {
    companion object {
        fun from(feed: CalendarFeedState): CalendarFilterOptions {
            val facets = feed.entriesByDay.values.flatten().map { it.facets }
            fun count(values: List<String>) = values.groupingBy { it }.eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
            return CalendarFilterOptions(
                genres = count(facets.flatMap { it.genres }),
                languages = count(facets.mapNotNull { it.language }),
                countries = count(facets.flatMap { it.countries }),
                services = CalendarStreamingService.entries.map { service ->
                    service to facets.count { service in it.services }
                },
                othersCount = facets.count { it.services.isEmpty() },
            )
        }
    }
}

@Composable
internal fun CalendarFiltersButton(activeCount: Int, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val active = activeCount > 0
    Surface(
        onClick = onClick,
        color = if (active) tokens.colors.accent.copy(alpha = 0.18f) else tokens.colors.surfaceCard,
        contentColor = if (active) tokens.colors.accent else tokens.colors.textSecondary,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s7),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(NuvioTokens.Icon.sm))
            Text(
                text = stringResource(Res.string.calendar_filters) + if (active) " · $activeCount" else "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Changes apply immediately so the calendar behind the dialog updates as chips are toggled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarFiltersDialog(
    sort: CalendarSort,
    refinement: CalendarRefinement,
    options: CalendarFilterOptions,
    /** Only the Global calendar knows where titles stream. */
    showServices: Boolean,
    onSortChange: (CalendarSort) -> Unit,
    onRefinementChange: (CalendarRefinement) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
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
                        listOf(
                            CalendarSort.All to Res.string.calendar_sort_all,
                            CalendarSort.New to Res.string.calendar_sort_new,
                            CalendarSort.Title to Res.string.calendar_sort_title,
                            CalendarSort.Rating to Res.string.calendar_sort_rating,
                        ).forEach { (value, label) ->
                            FilterChip(
                                label = stringResource(label),
                                selected = sort == value,
                                onClick = { onSortChange(value) },
                            )
                        }
                    }

                    if (options.genres.isEmpty() && options.languages.isEmpty() && options.countries.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.calendar_filters_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                    if (showServices) FilterSection(stringResource(Res.string.calendar_filter_services)) {
                        FilterChip(
                            label = stringResource(Res.string.calendar_services_all),
                            selected = refinement.services.isEmpty(),
                            onClick = { onRefinementChange(refinement.copy(services = emptySet())) },
                        )
                        options.services.forEach { (service, count) ->
                            FilterChip(
                                label = if (count > 0) "${service.displayName}  $count" else service.displayName,
                                selected = service.name in refinement.services,
                                onClick = {
                                    onRefinementChange(refinement.copy(services = refinement.services.toggle(service.name)))
                                },
                            )
                        }
                        val othersLabel = stringResource(Res.string.calendar_services_others)
                        FilterChip(
                            label = if (options.othersCount > 0) "$othersLabel  ${options.othersCount}" else othersLabel,
                            selected = CalendarStreamingService.OTHERS in refinement.services,
                            onClick = {
                                onRefinementChange(
                                    refinement.copy(services = refinement.services.toggle(CalendarStreamingService.OTHERS)),
                                )
                            },
                        )
                    }
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_genre),
                        options = options.genres,
                        selected = refinement.genres,
                        label = { it },
                        onToggle = { onRefinementChange(refinement.copy(genres = refinement.genres.toggle(it))) },
                    )
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_language),
                        options = options.languages,
                        selected = refinement.languages,
                        label = ::languageDisplayName,
                        onToggle = { onRefinementChange(refinement.copy(languages = refinement.languages.toggle(it))) },
                    )
                    FacetSection(
                        title = stringResource(Res.string.calendar_filter_country),
                        options = options.countries,
                        selected = refinement.countries,
                        label = ::countryDisplayName,
                        onToggle = { onRefinementChange(refinement.copy(countries = refinement.countries.toggle(it))) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    TextButton(
                        onClick = {
                            onSortChange(CalendarSort.All)
                            onRefinementChange(CalendarRefinement())
                        },
                    ) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacetSection(
    title: String,
    options: List<Pair<String, Int>>,
    selected: Set<String>,
    label: (String) -> String,
    onToggle: (String) -> Unit,
) {
    // Keep selections visible even when the current data no longer contains them.
    val shown = options + selected.filter { value -> options.none { it.first == value } }.map { it to 0 }
    if (shown.isEmpty()) return
    FilterSection(title) {
        shown.forEach { (value, count) ->
            FilterChip(
                label = if (count > 0) "${label(value)}  $count" else label(value),
                selected = value in selected,
                onClick = { onToggle(value) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSection(title: String, content: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textMuted,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            content()
        }
    }
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Surface(
        onClick = onClick,
        color = if (selected) tokens.colors.accent else tokens.colors.surfaceCard,
        contentColor = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

internal fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
