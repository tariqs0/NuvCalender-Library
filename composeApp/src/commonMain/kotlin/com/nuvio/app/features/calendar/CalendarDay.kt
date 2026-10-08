package com.nuvio.app.features.calendar

import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.features.watchprogress.CurrentDateProvider

/**
 * A civil calendar date backed by an epoch-day count, so range math, grids and map keys stay
 * plain integer arithmetic (the project does not ship kotlinx-datetime).
 */
internal class CalendarDay private constructor(val epochDay: Long) : Comparable<CalendarDay> {
    val year: Int
    val month: Int
    val dayOfMonth: Int

    init {
        val (y, m, d) = civilFromEpochDay(epochDay)
        year = y
        month = m
        dayOfMonth = d
    }

    /** ISO day of week: 1 = Monday … 7 = Sunday. 1970-01-01 was a Thursday. */
    val isoDayOfWeek: Int
        get() = (epochDay + 3).mod(7L).toInt() + 1

    val lengthOfMonth: Int
        get() = daysInMonth(year, month)

    fun plusDays(days: Int): CalendarDay = CalendarDay(epochDay + days)

    fun plusMonths(months: Int): CalendarDay {
        val totalMonths = year * 12L + (month - 1) + months
        val newYear = totalMonths.floorDiv(12L).toInt()
        val newMonth = totalMonths.mod(12L).toInt() + 1
        return of(newYear, newMonth, minOf(dayOfMonth, daysInMonth(newYear, newMonth)))
    }

    fun startOfMonth(): CalendarDay = of(year, month, 1)

    fun endOfMonth(): CalendarDay = of(year, month, lengthOfMonth)

    fun startOfWeek(firstDayOfWeek: Int = MONDAY): CalendarDay =
        plusDays(-(isoDayOfWeek - firstDayOfWeek).mod(7))

    fun daysUntil(other: CalendarDay): Int = (other.epochDay - epochDay).toInt()

    fun isSameMonth(other: CalendarDay): Boolean = year == other.year && month == other.month

    fun toIsoString(): String =
        year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" +
            dayOfMonth.toString().padStart(2, '0')

    override fun compareTo(other: CalendarDay): Int = epochDay.compareTo(other.epochDay)

    override fun equals(other: Any?): Boolean = other is CalendarDay && other.epochDay == epochDay

    override fun hashCode(): Int = epochDay.hashCode()

    override fun toString(): String = toIsoString()

    companion object {
        const val MONDAY = 1
        const val SATURDAY = 6
        const val SUNDAY = 7

        fun ofEpochDay(epochDay: Long): CalendarDay = CalendarDay(epochDay)

        fun of(year: Int, month: Int, dayOfMonth: Int): CalendarDay {
            require(month in 1..12) { "month out of range: $month" }
            require(dayOfMonth in 1..daysInMonth(year, month)) { "day out of range: $dayOfMonth" }
            return CalendarDay(epochDayFromCivil(year, month, dayOfMonth))
        }

        /** Accepts plain ISO dates as well as timestamps (converted to the viewer's local date). */
        fun parse(raw: String?): CalendarDay? {
            val iso = parseEpisodeReleaseLocalDate(raw) ?: return null
            val year = iso.substring(0, 4).toIntOrNull() ?: return null
            val month = iso.substring(5, 7).toIntOrNull() ?: return null
            val day = iso.substring(8, 10).toIntOrNull() ?: return null
            return runCatching { of(year, month, day) }.getOrNull()
        }

        fun today(): CalendarDay = parse(CurrentDateProvider.todayIsoDate()) ?: CalendarDay(0)
    }
}

/** Inclusive date range used for loading and filtering calendar entries. */
internal data class CalendarRange(val start: CalendarDay, val endInclusive: CalendarDay) {
    operator fun contains(day: CalendarDay): Boolean = day >= start && day <= endInclusive

    val days: List<CalendarDay>
        get() = (0..start.daysUntil(endInclusive)).map(start::plusDays)
}

internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> throw IllegalArgumentException("month out of range: $month")
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

// Howard Hinnant's days_from_civil / civil_from_days, valid for the whole proleptic Gregorian range.
private fun epochDayFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yearOfEra = y - era * 400
    val shiftedMonth = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

private fun civilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
    return Triple(year, month, day)
}
