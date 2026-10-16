package im.g.apps.feature.chat.timeline

import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.feature.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class TimelineMapperTest {

    private val ana = UserProfile("u-ana", "ana", "Ana Kovač")

    private fun message(id: String, at: String) = TimelineItem.Message(
        MessageItem(
            messageId = id,
            chatId = "c1",
            sender = ana,
            isOwn = false,
            sentAtMs = ms(at),
            content = MessageContent.Text(id),
            replyTo = null,
            reactions = emptyList(),
            edited = false,
            sendState = null,
        ),
    )

    /** What gim-core emits: separators at UTC day starts. */
    private fun utcSeparator(day: String) = TimelineItem.DateSeparator(ms("${day}T00:00:00Z"))

    private fun ms(at: String) = Instant.parse(at).toEpochMilli()

    private fun dayKey(day: String, zone: ZoneId) = "date-${LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()}"

    private fun map(zone: ZoneId, now: String, vararg items: TimelineItem) =
        TimelineMapper(zone, Locale.UK).map(items.toList(), isGroup = false, imagePaths = emptyMap(), nowMs = ms(now))

    @Test
    fun messageLateInTheUtcDay_landsInTheNextLocalDay_athens() {
        val zone = ZoneId.of("Europe/Athens")
        val rows = map(zone, "2026-10-15T09:00:00Z", utcSeparator("2026-10-14"), message("m1", "2026-10-14T22:54:00Z"))

        assertEquals(listOf("m1", dayKey("2026-10-15", zone)), rows.map { it.key })
        assertEquals("01:54", (rows[0] as MessageRow).time)
        assertEquals(UiText.Res(R.string.chat_today), (rows[1] as DateSeparatorRow).label)
    }

    @Test
    fun messageLateInTheUtcDay_landsInTheNextLocalDay_berlinSummer() {
        val zone = ZoneId.of("Europe/Berlin")
        val rows = map(zone, "2026-07-11T09:00:00Z", utcSeparator("2026-07-10"), message("m1", "2026-07-10T22:54:00Z"))

        assertEquals(listOf("m1", dayKey("2026-07-11", zone)), rows.map { it.key })
        assertEquals("00:54", (rows[0] as MessageRow).time)
        assertEquals(UiText.Res(R.string.chat_today), (rows[1] as DateSeparatorRow).label)
    }

    @Test
    fun messagesAcrossLocalMidnight_getTwoSeparators() {
        val zone = ZoneId.of("Europe/Berlin")
        // One UTC day in core, two local days.
        val rows = map(
            zone,
            "2026-07-11T09:00:00Z",
            utcSeparator("2026-07-10"),
            message("before", "2026-07-10T21:50:00Z"),
            message("after", "2026-07-10T22:01:00Z"),
        )

        assertEquals(
            listOf("after", dayKey("2026-07-11", zone), "before", dayKey("2026-07-10", zone)),
            rows.map { it.key },
        )
        assertEquals(UiText.Res(R.string.chat_today), (rows[1] as DateSeparatorRow).label)
        assertEquals(UiText.Res(R.string.chat_yesterday), (rows[3] as DateSeparatorRow).label)
        // Same sender within the grouping window, but a day apart: not grouped.
        assertFalse((rows[0] as MessageRow).groupedWithPrevious)
        assertFalse((rows[2] as MessageRow).groupedWithNext)
    }

    @Test
    fun utcZone_matchesCoreSeparators() {
        val items = arrayOf(
            utcSeparator("2026-10-12"),
            message("m1", "2026-10-12T10:00:00Z"),
            utcSeparator("2026-10-14"),
            message("m2", "2026-10-14T22:54:00Z"),
        )
        val rows = map(ZoneOffset.UTC, "2026-10-15T09:00:00Z", *items)

        assertEquals(listOf("m2", items[2].key(), "m1", items[0].key()), rows.map { it.key })
        assertEquals(UiText.Res(R.string.chat_yesterday), (rows[1] as DateSeparatorRow).label)
        val olderDay = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.UK)
            .format(LocalDate.of(2026, 10, 12))
        assertEquals(UiText.Raw(olderDay), (rows[3] as DateSeparatorRow).label)
    }

    @Test
    fun dstChange_keysSeparatorsByLocalMidnight() {
        val zone = ZoneId.of("Europe/Berlin")
        // Clocks go forward on 2026-03-29 at 01:00Z; that local day starts at 23:00Z the day before.
        val rows = map(
            zone,
            "2026-03-30T09:00:00Z",
            utcSeparator("2026-03-28"),
            message("m1", "2026-03-28T23:30:00Z"),
            utcSeparator("2026-03-29"),
            message("m2", "2026-03-29T22:30:00Z"),
        )

        assertEquals(
            listOf("m2", dayKey("2026-03-30", zone), "m1", dayKey("2026-03-29", zone)),
            rows.map { it.key },
        )
        assertEquals(dayKey("2026-03-29", zone), "date-${ms("2026-03-28T23:00:00Z")}")
        assertEquals("00:30", (rows[2] as MessageRow).time)
    }

    @Test
    fun unchangedRows_areReusedAcrossUpdates() {
        val zone = ZoneId.of("Europe/Athens")
        val mapper = TimelineMapper(zone, Locale.UK)
        val first = listOf(utcSeparator("2026-10-14"), message("m1", "2026-10-14T22:54:00Z"))
        val now = ms("2026-10-15T09:00:00Z")
        val before = mapper.map(first, isGroup = false, imagePaths = emptyMap(), nowMs = now)
        val after = mapper.map(
            first + message("m2", "2026-10-15T08:00:00Z"),
            isGroup = false,
            imagePaths = emptyMap(),
            nowMs = now,
        )

        assertEquals(listOf("m2", "m1", dayKey("2026-10-15", zone)), after.map { it.key })
        assertSame(before.last(), after.last())
    }

    private fun TimelineItem.key() = "date-${(this as TimelineItem.DateSeparator).dayStartMs}"
}
