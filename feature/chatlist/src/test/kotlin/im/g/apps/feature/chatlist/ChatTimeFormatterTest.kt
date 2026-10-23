package im.g.apps.feature.chatlist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatTimeFormatterTest {
    private val zone = ZoneId.of("Europe/Athens")

    // Sunday 2026-07-12 10:00 in Athens.
    private val now = ZonedDateTime.of(2026, 7, 12, 10, 0, 0, 0, zone)
    private val clock = Clock.fixed(now.toInstant(), zone)
    private val us = ChatTimeFormatter(clock, Locale.US)
    private val de = ChatTimeFormatter(clock, Locale.GERMANY)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) = at(2026, 7, day, hour, minute, second)

    /** Newer JDKs put a narrow no-break space before AM/PM. */
    private fun ChatTimeLabel.normalized(): ChatTimeLabel = when (this) {
        is ChatTimeLabel.Clock -> ChatTimeLabel.Clock(text.replace(' ', ' '))
        else -> this
    }

    @Test
    fun under_a_minute_is_just_now() {
        assertEquals(ChatTimeLabel.JustNow, us.format(at(12, 10, 0)))
        assertEquals(ChatTimeLabel.JustNow, us.format(at(12, 9, 59, 1)))
    }

    @Test
    fun under_an_hour_today_counts_minutes() {
        assertEquals(ChatTimeLabel.Minutes(1), us.format(at(12, 9, 59, 0)))
        assertEquals(ChatTimeLabel.Minutes(5), us.format(at(12, 9, 55)))
        assertEquals(ChatTimeLabel.Minutes(59), us.format(at(12, 9, 0, 1)))
    }

    @Test
    fun an_hour_or_more_today_shows_localized_clock_time() {
        assertEquals(ChatTimeLabel.Clock("9:00 AM"), us.format(at(12, 9, 0)).normalized())
        assertEquals(ChatTimeLabel.Clock("09:00"), de.format(at(12, 9, 0)))
        assertEquals(ChatTimeLabel.Clock("00:00"), de.format(at(12, 0, 0)))
    }

    @Test
    fun minutes_never_cross_midnight() {
        // 00:10 on Monday; a message from 23:55 on Sunday is 15 minutes old but from yesterday.
        val earlyClock = Clock.fixed(ZonedDateTime.of(2026, 7, 13, 0, 10, 0, 0, zone).toInstant(), zone)
        val formatter = ChatTimeFormatter(earlyClock, Locale.US)
        assertEquals(ChatTimeLabel.Yesterday, formatter.format(at(12, 23, 55)))
        assertEquals(ChatTimeLabel.Minutes(10), formatter.format(at(13, 0, 0)))
    }

    @Test
    fun previous_day_is_yesterday() {
        assertEquals(ChatTimeLabel.Yesterday, us.format(at(11, 23, 59, 59)))
        assertEquals(ChatTimeLabel.Yesterday, us.format(at(11, 0, 0)))
    }

    @Test
    fun two_to_six_days_ago_show_localized_weekday() {
        assertEquals(ChatTimeLabel.Weekday("Fri"), us.format(at(10, 12, 0)))
        assertEquals(ChatTimeLabel.Weekday("Fr."), de.format(at(10, 12, 0)))
        // Six days ago (Monday) is the last weekday label.
        assertEquals(ChatTimeLabel.Weekday("Mon"), us.format(at(6, 0, 0)))
    }

    @Test
    fun seven_days_ago_in_the_same_year_shows_day_and_month() {
        assertEquals(ChatTimeLabel.Date("Jul 5"), us.format(at(5, 23, 59)))
        assertEquals(ChatTimeLabel.Date("05.07"), de.format(at(5, 23, 59)))
        assertEquals(ChatTimeLabel.Date("Jan 1"), us.format(at(2026, 1, 1, 0, 0)))
    }

    @Test
    fun previous_years_show_localized_short_date() {
        assertEquals(ChatTimeLabel.Date("12/31/25"), us.format(at(2025, 12, 31, 23, 59)))
        assertEquals(ChatTimeLabel.Date("31.12.25"), de.format(at(2025, 12, 31, 23, 59)))
    }

    @Test
    fun new_year_uses_yesterday_then_weekday_then_short_date() {
        // Friday 2027-01-01 09:00.
        val newYear = Clock.fixed(ZonedDateTime.of(2027, 1, 1, 9, 0, 0, 0, zone).toInstant(), zone)
        val formatter = ChatTimeFormatter(newYear, Locale.US)
        assertEquals(ChatTimeLabel.Yesterday, formatter.format(at(2026, 12, 31, 22, 0)))
        assertEquals(ChatTimeLabel.Weekday("Sun"), formatter.format(at(2026, 12, 27, 22, 0)))
        assertEquals(ChatTimeLabel.Date("12/25/26"), formatter.format(at(2026, 12, 25, 22, 0)))
    }

    @Test
    fun future_timestamps_show_clock_time() {
        assertEquals(ChatTimeLabel.Clock("10:00"), de.format(at(12, 10, 0, 30)))
        assertEquals(ChatTimeLabel.Clock("10:05"), de.format(at(12, 10, 5)))
        assertEquals(ChatTimeLabel.Clock("08:00"), de.format(at(13, 8, 0)))
    }

    @Test
    fun day_boundary_uses_clock_zone() {
        // 23:30 UTC on the 11th is already 02:30 on the 12th in Athens.
        val lateUtc = ZonedDateTime.of(2026, 7, 11, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(ChatTimeLabel.Clock("02:30"), de.format(lateUtc))
    }
}
