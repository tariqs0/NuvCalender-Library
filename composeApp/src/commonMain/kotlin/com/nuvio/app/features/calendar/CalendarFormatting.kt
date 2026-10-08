package com.nuvio.app.features.calendar

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_episode_count
import nuvio.composeapp.generated.resources.calendar_episode_range
import nuvio.composeapp.generated.resources.calendar_episode_single
import nuvio.composeapp.generated.resources.calendar_kind_digital
import nuvio.composeapp.generated.resources.calendar_kind_episode
import nuvio.composeapp.generated.resources.calendar_kind_movie
import nuvio.composeapp.generated.resources.calendar_kind_season_premiere
import nuvio.composeapp.generated.resources.calendar_kind_series_premiere
import nuvio.composeapp.generated.resources.calendar_month_1
import nuvio.composeapp.generated.resources.calendar_month_10
import nuvio.composeapp.generated.resources.calendar_month_11
import nuvio.composeapp.generated.resources.calendar_month_12
import nuvio.composeapp.generated.resources.calendar_month_2
import nuvio.composeapp.generated.resources.calendar_month_3
import nuvio.composeapp.generated.resources.calendar_month_4
import nuvio.composeapp.generated.resources.calendar_month_5
import nuvio.composeapp.generated.resources.calendar_month_6
import nuvio.composeapp.generated.resources.calendar_month_7
import nuvio.composeapp.generated.resources.calendar_month_8
import nuvio.composeapp.generated.resources.calendar_month_9
import nuvio.composeapp.generated.resources.calendar_weekday_1
import nuvio.composeapp.generated.resources.calendar_weekday_2
import nuvio.composeapp.generated.resources.calendar_weekday_3
import nuvio.composeapp.generated.resources.calendar_weekday_4
import nuvio.composeapp.generated.resources.calendar_weekday_5
import nuvio.composeapp.generated.resources.calendar_weekday_6
import nuvio.composeapp.generated.resources.calendar_weekday_7
import nuvio.composeapp.generated.resources.calendar_weekday_short_1
import nuvio.composeapp.generated.resources.calendar_weekday_short_2
import nuvio.composeapp.generated.resources.calendar_weekday_short_3
import nuvio.composeapp.generated.resources.calendar_weekday_short_4
import nuvio.composeapp.generated.resources.calendar_weekday_short_5
import nuvio.composeapp.generated.resources.calendar_weekday_short_6
import nuvio.composeapp.generated.resources.calendar_weekday_short_7
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val MonthNames = listOf(
    Res.string.calendar_month_1, Res.string.calendar_month_2, Res.string.calendar_month_3,
    Res.string.calendar_month_4, Res.string.calendar_month_5, Res.string.calendar_month_6,
    Res.string.calendar_month_7, Res.string.calendar_month_8, Res.string.calendar_month_9,
    Res.string.calendar_month_10, Res.string.calendar_month_11, Res.string.calendar_month_12,
)

private val WeekdayNames = listOf(
    Res.string.calendar_weekday_1, Res.string.calendar_weekday_2, Res.string.calendar_weekday_3,
    Res.string.calendar_weekday_4, Res.string.calendar_weekday_5, Res.string.calendar_weekday_6,
    Res.string.calendar_weekday_7,
)

private val WeekdayShortNames = listOf(
    Res.string.calendar_weekday_short_1, Res.string.calendar_weekday_short_2, Res.string.calendar_weekday_short_3,
    Res.string.calendar_weekday_short_4, Res.string.calendar_weekday_short_5, Res.string.calendar_weekday_short_6,
    Res.string.calendar_weekday_short_7,
)

@Composable
internal fun monthName(month: Int): String = stringResource(MonthNames[month - 1])

@Composable
internal fun weekdayName(isoDayOfWeek: Int): String = stringResource(WeekdayNames[isoDayOfWeek - 1])

@Composable
internal fun weekdayShortName(isoDayOfWeek: Int): String = stringResource(WeekdayShortNames[isoDayOfWeek - 1])

/** e.g. "Wednesday, 7 October 2026" (date part localised by the platform formatter). */
@Composable
internal fun formatCalendarDayLong(day: CalendarDay): String =
    weekdayName(day.isoDayOfWeek) + ", " + formatReleaseDateForDisplay(day.toIsoString())

@Composable
internal fun calendarPeriodTitle(mode: CalendarViewMode, range: CalendarRange, focus: CalendarDay): String =
    when (mode) {
        CalendarViewMode.Month -> "${monthName(focus.month)} ${focus.year}"
        CalendarViewMode.Week ->
            formatReleaseDateWithoutYear(range.start.toIsoString()) + " – " +
                formatReleaseDateForDisplay(range.endInclusive.toIsoString())
        CalendarViewMode.Day -> formatCalendarDayLong(focus)
    }

@Composable
internal fun calendarEpisodeLine(entry: CalendarEntry): String? {
    val season = entry.season ?: return null
    val episode = entry.episode ?: return null
    val code = entry.lastEpisode?.let { last ->
        stringResource(Res.string.calendar_episode_range, season, episode, last)
    } ?: stringResource(Res.string.calendar_episode_single, season, episode)
    val suffix = entry.episodeTitle
        ?: entry.episodes.size.takeIf { it > 1 }?.let { stringResource(Res.string.calendar_episode_count, it) }
    return if (suffix != null) "$code · $suffix" else code
}

internal fun CalendarEntryKind.labelRes(): StringResource = when (this) {
    CalendarEntryKind.MovieRelease -> Res.string.calendar_kind_movie
    CalendarEntryKind.MovieDigitalRelease -> Res.string.calendar_kind_digital
    CalendarEntryKind.SeriesPremiere -> Res.string.calendar_kind_series_premiere
    CalendarEntryKind.SeasonPremiere -> Res.string.calendar_kind_season_premiere
    CalendarEntryKind.Episode -> Res.string.calendar_kind_episode
}

/** One hue per kind so a month grid can be scanned at a glance. */
@Composable
internal fun CalendarEntryKind.accentColor(): Color {
    val colors = MaterialTheme.nuvio.colors
    return when (this) {
        CalendarEntryKind.MovieRelease -> colors.accent
        CalendarEntryKind.MovieDigitalRelease -> colors.info
        CalendarEntryKind.SeriesPremiere -> colors.success
        CalendarEntryKind.SeasonPremiere -> colors.warning
        CalendarEntryKind.Episode -> colors.neutral
    }
}

@Composable
internal fun CalendarKindBadge(kind: CalendarEntryKind, modifier: Modifier = Modifier) {
    val color = kind.accentColor()
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.18f),
        contentColor = color,
        shape = MaterialTheme.nuvio.shapes.chip,
    ) {
        Text(
            text = stringResource(kind.labelRes()),
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s3),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
