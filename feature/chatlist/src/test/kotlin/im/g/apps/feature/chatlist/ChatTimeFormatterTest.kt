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
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 7, 12, 10, 0, 0, 0, zone).toInstant(), zone)

    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 7, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun today_shows_time_of_day() {
        val formatter = ChatTimeFormatter(clock, Locale.UK)
        assertEquals("08:05", formatter.format(at(12, 8, 5)))
        assertEquals("00:00", formatter.format(at(12, 0, 0)))
    }

    @Test
    fun previous_six_days_show_weekday() {
        val formatter = ChatTimeFormatter(clock, Locale.UK)
        assertEquals("Sat", formatter.format(at(11, 23, 59)))
        assertEquals("Fri", formatter.format(at(10, 12, 0)))
        assertEquals("Mon", formatter.format(at(6, 9, 0)))
    }

    @Test
    fun older_dates_show_short_date() {
        val formatter = ChatTimeFormatter(clock, Locale.UK)
        assertEquals("05.07.26", formatter.format(at(5, 18, 0)))
        assertEquals("28.12.25", formatter.format(ZonedDateTime.of(2025, 12, 28, 9, 0, 0, 0, zone).toInstant().toEpochMilli()))
    }

    @Test
    fun weekday_is_localized_and_day_boundary_uses_clock_zone() {
        assertEquals("Fr.", ChatTimeFormatter(clock, Locale.GERMANY).format(at(10, 12, 0)))
        // 23:30 UTC on the 11th is already 02:30 on the 12th in Athens.
        val lateUtc = ZonedDateTime.of(2026, 7, 11, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("02:30", ChatTimeFormatter(clock, Locale.UK).format(lateUtc))
    }
}
