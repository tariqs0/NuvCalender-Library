package com.nuvio.app.features.calendar

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarDayTest {

    @Test
    fun epochDayRoundTripsAcrossLeapYearsAndCenturies() {
        assertEquals(0L, CalendarDay.of(1970, 1, 1).epochDay)
        assertEquals("2000-02-29", CalendarDay.of(2000, 2, 29).toIsoString())
        assertEquals("2024-03-01", CalendarDay.of(2024, 2, 29).plusDays(1).toIsoString())
        assertEquals("2100-03-01", CalendarDay.of(2100, 2, 28).plusDays(1).toIsoString())
        assertEquals("1969-12-31", CalendarDay.ofEpochDay(-1).toIsoString())
        for (epochDay in -800_000L..800_000L step 997) {
            val day = CalendarDay.ofEpochDay(epochDay)
            assertEquals(epochDay, CalendarDay.of(day.year, day.month, day.dayOfMonth).epochDay)
        }
    }

    @Test
    fun dayOfWeekFollowsIso() {
        assertEquals(4, CalendarDay.of(1970, 1, 1).isoDayOfWeek) // Thursday
        assertEquals(3, CalendarDay.of(2026, 10, 7).isoDayOfWeek) // Wednesday
        assertEquals(7, CalendarDay.of(1969, 12, 28).isoDayOfWeek) // Sunday before the epoch
    }

    @Test
    fun plusMonthsClampsToShorterMonths() {
        assertEquals("2025-02-28", CalendarDay.of(2025, 1, 31).plusMonths(1).toIsoString())
        assertEquals("2024-02-29", CalendarDay.of(2024, 1, 31).plusMonths(1).toIsoString())
        assertEquals("2025-12-15", CalendarDay.of(2026, 1, 15).plusMonths(-1).toIsoString())
    }

    @Test
    fun parsesPlainDatesAndRejectsYearOnlyValues() {
        assertEquals("2026-10-07", CalendarDay.parse("2026-10-07")?.toIsoString())
        assertNull(CalendarDay.parse("2026"))
        assertNull(CalendarDay.parse("2026-02-30"))
        assertNull(CalendarDay.parse(null))
    }

    @Test
    fun monthViewCoversSixWeeksStartingOnFirstDayOfWeek() {
        val range = CalendarViewMode.Month.visibleRange(CalendarDay.of(2026, 10, 7), CalendarDay.MONDAY)
        assertEquals("2026-09-28", range.start.toIsoString())
        assertEquals(MonthGridDayCount, range.days.size)
        assertEquals(CalendarDay.MONDAY, range.start.isoDayOfWeek)
        assertTrue(CalendarDay.of(2026, 10, 31) in range)
    }

    @Test
    fun weekAndDayViewsStepByTheirOwnSpan() {
        val focus = CalendarDay.of(2026, 10, 7)
        val week = CalendarViewMode.Week.visibleRange(focus, CalendarDay.MONDAY)
        assertEquals("2026-10-05", week.start.toIsoString())
        assertEquals("2026-10-11", week.endInclusive.toIsoString())
        assertEquals("2026-10-14", CalendarViewMode.Week.step(focus, 1).toIsoString())
        assertEquals("2026-10-06", CalendarViewMode.Day.step(focus, -1).toIsoString())
        assertEquals("2026-11-07", CalendarViewMode.Month.step(focus, 1).toIsoString())
    }

    @Test
    fun weeksCanStartOnSundayOrSaturday() {
        val focus = CalendarDay.of(2026, 10, 7) // Wednesday
        val sundayWeek = CalendarViewMode.Week.visibleRange(focus, CalendarDay.SUNDAY)
        assertEquals("2026-10-04", sundayWeek.start.toIsoString())
        assertEquals("2026-10-10", sundayWeek.endInclusive.toIsoString())
        val saturdayWeek = CalendarViewMode.Week.visibleRange(focus, CalendarDay.SATURDAY)
        assertEquals("2026-10-03", saturdayWeek.start.toIsoString())
        val saturdayMonth = CalendarViewMode.Month.visibleRange(focus, CalendarDay.SATURDAY)
        assertEquals(CalendarDay.SATURDAY, saturdayMonth.start.isoDayOfWeek)
        assertTrue(CalendarDay.of(2026, 10, 1) in saturdayMonth)
    }

    @Test
    fun layoutSettingsRoundTripAndRejectUnsupportedWeekStarts() {
        val state = CalendarSettingsUiState(firstDayOfWeek = CalendarDay.SATURDAY, density = CalendarDensity.Spacious)
        assertEquals(state, decodeCalendarSettings(encodeCalendarSettings(state)))
        val wednesday = decodeCalendarSettings("""{"first_day_of_week":3,"density":"Huge"}""")
        assertEquals(CalendarDay.MONDAY, wednesday.firstDayOfWeek)
        assertEquals(CalendarDensity.Comfortable, wednesday.density)
    }

    @Test
    fun entriesDescribeEpisodeRanges() {
        val preview = MetaPreview(id = "tt1", type = "series", name = "Show")
        val binge = CalendarEntry(
            day = CalendarDay.of(2026, 10, 7),
            preview = preview,
            kind = CalendarEntryKind.SeasonPremiere,
            episodes = (1..8).map { CalendarEpisode(season = 2, episode = it, videoId = "tt1:2:$it", title = "Ep $it") },
        )
        assertTrue(binge.isEpisodic)
        assertEquals(2, binge.season)
        assertEquals(1, binge.episode)
        assertEquals(8, binge.lastEpisode)
        assertNull(binge.episodeTitle)

        val movie = CalendarEntry(day = binge.day, preview = preview.copy(type = "movie"), kind = CalendarEntryKind.MovieRelease)
        assertFalse(movie.isEpisodic)
        assertEquals(
            listOf(CalendarEntryKind.SeasonPremiere, CalendarEntryKind.MovieRelease),
            listOf(movie, binge).sortedForDisplay().map { it.kind },
        )
    }

    @Test
    fun settingsRoundTripAndHiddenGlobalFallsBackToPersonal() {
        val state = CalendarSettingsUiState(
            globalCalendarEnabled = false,
            viewMode = CalendarViewMode.Week,
            scope = CalendarScope.Global,
        )
        val decoded = decodeCalendarSettings(encodeCalendarSettings(state))
        assertEquals(state, decoded)
        assertEquals(CalendarScope.Personal, decoded.effectiveScope)
        assertEquals(CalendarSettingsUiState(), decodeCalendarSettings("not json"))
    }
}
