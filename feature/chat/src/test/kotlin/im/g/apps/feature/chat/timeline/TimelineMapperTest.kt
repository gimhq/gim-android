package im.g.apps.feature.chat.timeline

import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.feature.chat.R
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class TimelineMapperTest {

    private val ana = UserProfile("u-ana", "ana", "Ana Kovač")
    private val athens = ZoneId.of("Europe/Athens")

    private fun message(id: String, at: String, sender: UserProfile = ana, content: MessageContent = MessageContent.Text(id)) =
        TimelineItem.Message(
            MessageItem(
                messageId = id,
                chatId = "c1",
                sender = sender,
                isOwn = false,
                sentAtMs = ms(at),
                content = content,
                replyTo = null,
                reactions = emptyList(),
                edited = false,
                sendState = null,
            ),
        )

    /** What gim-core emits once `setLocalTimeZone` is set: separators at local day starts. */
    private fun separator(day: String, zone: ZoneId = athens) =
        TimelineItem.DateSeparator(LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli())

    private fun ms(at: String) = Instant.parse(at).toEpochMilli()

    private fun map(now: String, vararg items: TimelineItem, isGroup: Boolean = false, unavailable: Set<String> = emptySet()) =
        TimelineMapper(athens, Locale.UK).map(items.toList(), isGroup, emptyMap(), ms(now), unavailable)

    @Test
    fun coreSeparators_areKeptAndKeyedByTheirDayStart() {
        // 22:54Z on the 14th is 01:54 on the 15th in Athens; core buckets it there.
        val items = arrayOf(
            separator("2026-10-12"),
            message("m1", "2026-10-12T10:00:00Z"),
            separator("2026-10-15"),
            message("m2", "2026-10-14T22:54:00Z"),
        )
        val rows = map("2026-10-15T09:00:00Z", *items)

        assertEquals(listOf("m2", items[2].key(), "m1", items[0].key()), rows.map { it.key })
        assertEquals("01:54", (rows[0] as MessageRow).time)
        assertEquals(UiText.Res(R.string.chat_today), (rows[1] as DateSeparatorRow).label)
        val olderDay = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.UK)
            .format(LocalDate.of(2026, 10, 12))
        assertEquals(UiText.Raw(olderDay), (rows[3] as DateSeparatorRow).label)
    }

    @Test
    fun separatorLabels_areLocalDays_acrossDst() {
        // Clocks go back on 2026-10-25 in Athens; the 26th still starts at local midnight (22:00Z).
        val rows = map(
            "2026-10-26T09:00:00Z",
            separator("2026-10-25"),
            message("m1", "2026-10-25T08:00:00Z"),
            separator("2026-10-26"),
            message("m2", "2026-10-26T08:00:00Z"),
        )

        assertEquals("date-${ms("2026-10-25T22:00:00Z")}", rows[1].key)
        assertEquals(UiText.Res(R.string.chat_today), (rows[1] as DateSeparatorRow).label)
        assertEquals(UiText.Res(R.string.chat_yesterday), (rows[3] as DateSeparatorRow).label)
    }

    @Test
    fun noSeparatorsAreInvented() {
        // Two local days without a core separator between them: the mapper no longer re-buckets.
        val rows = map(
            "2026-07-11T09:00:00Z",
            separator("2026-07-10"),
            message("before", "2026-07-10T20:58:00Z"),
            message("after", "2026-07-10T21:01:00Z"),
        )

        assertEquals(listOf("after", "before", separator("2026-07-10").key()), rows.map { it.key })
        assertTrue((rows[0] as MessageRow).groupedWithPrevious)
    }

    @Test
    fun unchangedRows_areReusedAcrossUpdates() {
        val mapper = TimelineMapper(athens, Locale.UK)
        val first = listOf(separator("2026-10-15"), message("m1", "2026-10-14T22:54:00Z"))
        val now = ms("2026-10-15T09:00:00Z")
        val before = mapper.map(first, isGroup = false, imagePaths = emptyMap(), nowMs = now)
        val after = mapper.map(first + message("m2", "2026-10-15T08:00:00Z"), isGroup = false, imagePaths = emptyMap(), nowMs = now)

        assertEquals(listOf("m2", "m1", first[0].key()), after.map { it.key })
        assertSame(before.last(), after.last())
    }

    @Test
    fun unresolvedNames_showAShortUserId() {
        val remoteId = "u" + "b".repeat(54) + "q"
        val remote = UserProfile(remoteId, "", remoteId)
        val rows = map(
            "2026-10-15T09:00:00Z",
            separator("2026-10-15"),
            TimelineItem.MembershipChange(remoteId, MembershipChangeKind.ADDED, ms("2026-10-15T07:00:00Z")),
            message("m1", "2026-10-15T08:00:00Z", sender = remote),
            isGroup = true,
        )

        assertEquals("bbbbbbbb", (rows[0] as MessageRow).senderName)
        assertEquals(UiText.Res(R.string.chat_member_added, persistentListOf("bbbbbbbb")), (rows[1] as SystemRow).text)
    }

    @Test
    fun unavailableMedia_offersARetryOnItsRowOnly() {
        val image = MessageContent.Image(MediaRef("c1", "img", "image/jpeg", 10, hasThumbnail = true), 4, 3, null)
        val file = MessageContent.File(MediaRef("c1", "doc", "application/pdf", 10, hasThumbnail = false), "route.pdf", null)
        val rows = map(
            "2026-10-15T09:00:00Z",
            separator("2026-10-15"),
            message("img", "2026-10-15T07:00:00Z", content = image),
            message("doc", "2026-10-15T07:01:00Z", content = file),
            unavailable = setOf("img"),
        )

        assertTrue((rows.single { it.key == "img" } as MessageRow).mediaUnavailable)
        assertFalse((rows.single { it.key == "doc" } as MessageRow).mediaUnavailable)
        assertNull((rows.single { it.key == "img" } as MessageRow).image?.path)
    }

    private fun TimelineItem.key() = "date-${(this as TimelineItem.DateSeparator).dayStartMs}"
}
