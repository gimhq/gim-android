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
    private companion object {
        const val SELF = "u-self"
        val NAMES = mapOf("u-ana" to "Ana", "u-bob" to "Bob", SELF to "Sofia")
    }

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

    private fun map(
        now: String,
        vararg items: TimelineItem,
        isGroup: Boolean = false,
        unavailable: Set<String> = emptySet(),
        locale: Locale = Locale.UK,
    ) = TimelineMapper(athens, locale).map(items.toList(), isGroup, emptyMap(), ms(now), unavailable)

    private fun membership(userId: String, at: String) =
        TimelineItem.MembershipChange(userId, MembershipChangeKind.ADDED, ms(at), userId, null)

    private fun membershipText(
        change: MembershipChangeKind,
        userId: String,
        actorUserId: String?,
        userName: String = userId,
        names: Map<String, String> = NAMES,
    ): UiText {
        val item = TimelineItem.MembershipChange(userName, change, ms("2026-10-15T07:00:00Z"), userId, actorUserId)
        val rows = TimelineMapper(athens, Locale.UK)
            .map(listOf(item), isGroup = true, emptyMap(), ms("2026-10-15T09:00:00Z"), emptySet(), SELF, names)
        return (rows.single() as SystemRow).text
    }

    private fun res(id: Int, vararg args: String) = UiText.Res(id, persistentListOf(*args))

    @Test
    fun membershipChanges_renderFromUserIds() {
        val ana = "u-ana"
        val bob = "u-bob"
        assertEquals(res(R.string.chat_member_removed_by, "Ana", "Bob"), membershipText(MembershipChangeKind.REMOVED, bob, ana))
        assertEquals(res(R.string.chat_member_added_by, "Bob", "Ana"), membershipText(MembershipChangeKind.ADDED, ana, bob))
        assertEquals(res(R.string.chat_member_you_added, "Bob"), membershipText(MembershipChangeKind.ADDED, bob, SELF))
        assertEquals(res(R.string.chat_member_you_removed, "Bob"), membershipText(MembershipChangeKind.REMOVED, bob, SELF))
        assertEquals(res(R.string.chat_member_added_you, "Ana"), membershipText(MembershipChangeKind.ADDED, SELF, ana))
        assertEquals(res(R.string.chat_member_removed_you), membershipText(MembershipChangeKind.REMOVED, SELF, ana))
        assertEquals(res(R.string.chat_member_you_left), membershipText(MembershipChangeKind.LEFT, SELF, SELF))
        assertEquals(res(R.string.chat_member_left, "Bob"), membershipText(MembershipChangeKind.LEFT, bob, bob))
        // Unknown actor: no "by".
        assertEquals(res(R.string.chat_member_added, "Bob"), membershipText(MembershipChangeKind.ADDED, bob, null))
        assertEquals(res(R.string.chat_member_removed, "Bob"), membershipText(MembershipChangeKind.REMOVED, bob, null))
    }

    @Test
    fun membershipChanges_neverMatchTheOwnUserByName() {
        // Another user with the signed-in user's display name is not "you", and the own change is "you" even while
        // gim-core still shows the bare user id as its name.
        assertEquals(
            res(R.string.chat_member_left, "Sofia"),
            membershipText(MembershipChangeKind.LEFT, "u-other", "u-other", userName = "Sofia", names = emptyMap()),
        )
        assertEquals(
            res(R.string.chat_member_you_left),
            membershipText(MembershipChangeKind.LEFT, SELF, SELF, userName = SELF, names = emptyMap()),
        )
        // Names come from the lookup by id first, then from the change itself.
        assertEquals(
            res(R.string.chat_member_removed_by, "Ana", "Lukas"),
            membershipText(MembershipChangeKind.REMOVED, "u-lukas", "u-ana", userName = "Lukas", names = mapOf("u-ana" to "Ana")),
        )
    }

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
        assertEquals(UiText.Raw("Monday"), (rows[3] as DateSeparatorRow).label)
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
            TimelineItem.MembershipChange(remoteId, MembershipChangeKind.ADDED, ms("2026-10-15T07:00:00Z"), remoteId, null),
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

    @Test
    fun grouping_breaksAtDateSeparatorSystemRowSenderAndWindow() {
        val bob = UserProfile("u-bob", "bob", "Bob")
        val rows = map(
            "2026-10-16T09:00:00Z",
            separator("2026-10-14"),
            // 23:58 and 00:01 local, two minutes apart but on two days.
            message("lateNight", "2026-10-14T20:58:00Z"),
            separator("2026-10-15"),
            message("afterMidnight", "2026-10-14T21:01:00Z"),
            membership("u-bob", "2026-10-15T07:00:00Z"),
            message("afterSystem", "2026-10-15T07:01:00Z"),
            message("sameRun", "2026-10-15T07:05:00Z"),
            message("otherSender", "2026-10-15T07:06:00Z", sender = bob),
            message("bobAgain", "2026-10-15T07:12:00Z", sender = bob),
            isGroup = true,
        )
        fun row(id: String) = rows.single { it.key == id } as MessageRow

        assertFalse(row("lateNight").groupedWithNext)
        assertFalse(row("afterMidnight").groupedWithPrevious)
        assertFalse(row("afterMidnight").groupedWithNext)
        assertFalse(row("afterSystem").groupedWithPrevious)
        assertTrue(row("afterSystem").groupedWithNext)
        assertTrue(row("sameRun").groupedWithPrevious)
        assertFalse(row("sameRun").groupedWithNext)
        assertFalse(row("otherSender").groupedWithPrevious)
        // Six minutes is outside the grouping window.
        assertFalse(row("otherSender").groupedWithNext)
        assertFalse(row("bobAgain").groupedWithPrevious)
    }

    @Test
    fun groupInboundMessages_carryTheSenderForAvatarAndTalkBack() {
        val rows = map(
            "2026-10-15T09:00:00Z",
            separator("2026-10-15"),
            message("m1", "2026-10-15T07:00:00Z"),
            message("m2", "2026-10-15T07:01:00Z"),
            isGroup = true,
        )
        val first = rows.single { it.key == "m1" } as MessageRow
        val second = rows.single { it.key == "m2" } as MessageRow

        assertEquals(SenderUi("u-ana", "Ana Kovač"), first.sender)
        assertEquals(SenderUi("u-ana", "Ana Kovač"), second.sender)
        assertEquals("Ana Kovač", first.senderName)
        assertNull(second.senderName)
        assertFalse(first.showAvatar)
        assertTrue(second.showAvatar)

        val direct = map("2026-10-15T09:00:00Z", message("m1", "2026-10-15T07:00:00Z"))
        assertNull((direct.single() as MessageRow).sender)
    }

    @Test
    fun dateLabels_areLocalized_enUs() {
        assertEquals(
            listOf(
                UiText.Res(R.string.chat_today),
                UiText.Res(R.string.chat_yesterday),
                UiText.Raw("Tuesday"),
                UiText.Raw("October 8"),
                UiText.Raw(mediumDate(Locale.US, LocalDate.of(2025, 12, 24))),
            ),
            dayLabels(Locale.US),
        )
    }

    @Test
    fun dateLabels_areLocalized_deDe() {
        assertEquals(
            listOf(
                UiText.Res(R.string.chat_today),
                UiText.Res(R.string.chat_yesterday),
                UiText.Raw("Dienstag"),
                UiText.Raw("8. Oktober"),
                UiText.Raw(mediumDate(Locale.GERMANY, LocalDate.of(2025, 12, 24))),
            ),
            dayLabels(Locale.GERMANY),
        )
    }

    @Test
    fun messageTimes_followTheLocale() {
        // 22:54Z and 10:54Z are 01:54 and 13:54 in Athens.
        fun times(locale: Locale) = map(
            "2026-10-15T12:00:00Z",
            separator("2026-10-15"),
            message("m1", "2026-10-14T22:54:00Z"),
            message("m2", "2026-10-15T10:54:00Z"),
            locale = locale,
        ).filterIsInstance<MessageRow>().map { it.time.replace('\u202f', ' ') }

        assertEquals(listOf("1:54 PM", "1:54 AM"), times(Locale.US))
        assertEquals(listOf("下午1:54", "上午1:54"), times(Locale.SIMPLIFIED_CHINESE))
        assertEquals(listOf("13:54", "01:54"), times(Locale.UK))
    }

    @Test
    fun fileSizes_keepUnits_andFormatTheNumberForTheLocale() {
        assertEquals("512 B", formatSize(512, Locale.US))
        assertEquals("1.5 MB", formatSize(1_572_864, Locale.US))
        assertEquals("1,5 MB", formatSize(1_572_864, Locale.forLanguageTag("es")))
        assertEquals("1,5 MB", formatSize(1_572_864, Locale.GERMANY))
        assertEquals("471 KB", formatSize(482_304, Locale.forLanguageTag("es")))
        assertEquals("2.0 GB", formatSize(2_147_483_648, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun dayMonthPattern_dropsTheYearAndItsSeparators() {
        assertEquals("MMMM d", dayMonthPattern("MMMM d, y"))
        assertEquals("d. MMMM", dayMonthPattern("d. MMMM y"))
        assertEquals("M月d日", dayMonthPattern("y年M月d日"))
        assertEquals("d 'de' MMMM", dayMonthPattern("d 'de' MMMM 'de' y"))
        assertEquals("d MMMM", dayMonthPattern("d MMMM y 'г'."))
    }

    /** Today (Thursday), yesterday, two days ago, a week ago and last year, newest first. */
    private fun dayLabels(locale: Locale): List<UiText> = map(
        "2026-10-15T09:00:00Z",
        separator("2025-12-24"),
        separator("2026-10-08"),
        separator("2026-10-13"),
        separator("2026-10-14"),
        separator("2026-10-15"),
        locale = locale,
    ).map { (it as DateSeparatorRow).label }

    private fun mediumDate(locale: Locale, day: LocalDate) =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)

    private fun TimelineItem.key() = "date-${(this as TimelineItem.DateSeparator).dayStartMs}"
}
