package com.nuvio.app.features.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_home_row_empty
import nuvio.composeapp.generated.resources.calendar_home_row_title
import nuvio.composeapp.generated.resources.calendar_today
import nuvio.composeapp.generated.resources.calendar_tomorrow
import org.jetbrains.compose.resources.stringResource

/** How far ahead the Home row looks for Library releases. */
private const val HomeRowLookAheadDays = 30
private const val HomeRowMaxItems = 30

/**
 * Upcoming releases from the user's Library / Watchlist as a Home shelf: each title's next
 * release, soonest first. "View all" opens the Calendar page, so the calendar stays one tap away
 * even when it is hidden from navigation.
 */
@Composable
fun HomeLibraryCalendarRow(
    sectionPadding: Dp,
    onOpenCalendar: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { CalendarRepository.ensurePersonalLoaded() }
    val feed by CalendarRepository.personalState.collectAsStateWithLifecycle()
    val isWatched = rememberCalendarWatchedResolver()
    val today = remember { CalendarDay.today() }
    val upcoming = remember(feed, today) { nextLibraryReleases(feed, today) }
    val title = stringResource(Res.string.calendar_home_row_title)
    val todayLabel = stringResource(Res.string.calendar_today)
    val tomorrowLabel = stringResource(Res.string.calendar_tomorrow)

    if (upcoming.isEmpty()) {
        // Keep the shortcut to the Calendar visible even when nothing is coming up.
        Column(modifier = modifier.fillMaxWidth()) {
            NuvioShelfSection(
                title = title,
                entries = emptyList<CalendarEntry>(),
                headerHorizontalPadding = sectionPadding,
                onViewAllClick = onOpenCalendar,
                onTitleClick = onOpenCalendar?.takeIf { isDesktop },
                viewAllPillSize = NuvioViewAllPillSize.Compact,
            ) {}
            if (feed.hasLoaded && !feed.isLoading) {
                Text(
                    text = stringResource(Res.string.calendar_home_row_empty),
                    modifier = Modifier.padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s8),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
        return
    }

    val dayLabel: @Composable (CalendarDay) -> String = { day ->
        when (today.daysUntil(day)) {
            0 -> todayLabel
            1 -> tomorrowLabel
            else -> weekdayShortName(day.isoDayOfWeek) + ", " + formatReleaseDateWithoutYear(day.toIsoString())
        }
    }

    NuvioShelfSection(
        title = title,
        entries = upcoming,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        onViewAllClick = onOpenCalendar,
        onTitleClick = onOpenCalendar?.takeIf { isDesktop },
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { entry -> entry.key },
    ) { entry ->
        val code = entry.season?.let { season -> entry.episode?.let { " · S${season}E$it" } }.orEmpty()
        HomePosterCard(
            // The card's detail line shows releaseInfo, so it carries the release day instead.
            item = entry.preview.copy(releaseInfo = dayLabel(entry.day) + code),
            isWatched = isWatched(entry),
            onClick = onPosterClick?.let { { it(entry.preview) } },
            onLongClick = onPosterLongClick?.let { { it(entry.preview) } },
        )
    }
}

/** Each Library title's next release within the look-ahead window, soonest first. */
internal fun nextLibraryReleases(feed: CalendarFeedState, today: CalendarDay): List<CalendarEntry> =
    (0..HomeRowLookAheadDays)
        .asSequence()
        .flatMap { offset -> feed.entriesFor(today.plusDays(offset)).sortedForDisplay().asSequence() }
        .distinctBy { "${it.preview.type}:${it.preview.id}" }
        .take(HomeRowMaxItems)
        .toList()
