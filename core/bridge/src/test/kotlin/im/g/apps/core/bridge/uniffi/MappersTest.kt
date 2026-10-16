package im.g.apps.core.bridge.uniffi

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.MessagePreview
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Builds im.g.gimcore records directly: they are plain Kotlin classes, so no native library is loaded.
class MappersTest {
    private val coreUser = im.g.gimcore.UserProfile("u1", "alice", "Alice")
    private val user = UserProfile("u1", "alice", "Alice")
    private val coreMedia = im.g.gimcore.MediaRef("c1", "m1", "image/png", 4_000_000_000UL, true)
    private val media = MediaRef("c1", "m1", "image/png", 4_000_000_000L, true)

    private val coreSummary = im.g.gimcore.ChatSummary(
        chatId = "c1",
        kind = im.g.gimcore.ChatKind.GROUP,
        name = "Crew",
        avatar = coreMedia,
        lastMessage = im.g.gimcore.MessagePreview("Alice", "hi", 1_700_000_000_000UL, true),
        unreadCount = 3u,
        updatedAtMs = 1_700_000_000_001UL,
        memberCount = 4u,
        state = im.g.gimcore.ChatState.LEAVING,
    )
    private val summary = ChatSummary(
        chatId = "c1",
        kind = ChatKind.GROUP,
        name = "Crew",
        avatar = media,
        lastMessage = MessagePreview("Alice", "hi", 1_700_000_000_000L, true),
        unreadCount = 3,
        updatedAtMs = 1_700_000_000_001L,
        memberCount = 4,
        state = ChatState.LEAVING,
    )

    private val coreMessage = im.g.gimcore.MessageItem(
        messageId = "m1",
        chatId = "c1",
        sender = coreUser,
        isOwn = true,
        sentAtMs = 1_700_000_000_000UL,
        content = im.g.gimcore.MessageContent.Text("hello"),
        replyTo = im.g.gimcore.ReplyPreview("m0", "Bob", "earlier"),
        reactions = listOf(im.g.gimcore.ReactionGroup("+1", 2u, true)),
        edited = true,
        sendState = im.g.gimcore.SendState.Delivered,
    )
    private val message = MessageItem(
        messageId = "m1",
        chatId = "c1",
        sender = user,
        isOwn = true,
        sentAtMs = 1_700_000_000_000L,
        content = MessageContent.Text("hello"),
        replyTo = ReplyPreview("m0", "Bob", "earlier"),
        reactions = listOf(ReactionGroup("+1", 2, true)),
        edited = true,
        sendState = SendState.Delivered,
    )
    private val coreItem = im.g.gimcore.TimelineItem.Message(coreMessage)
    private val item = TimelineItem.Message(message)

    @Test
    fun session() {
        assertEquals(
            Session("u1", "d1", "alice", "Alice"),
            im.g.gimcore.Session("u1", "d1", "alice", "Alice").toMirror(),
        )
    }

    @Test
    fun sessionWithoutHandle() {
        assertEquals(Session("u1", "d1", "", "Alice"), im.g.gimcore.Session("u1", "d1", "", "Alice").toMirror())
    }

    @Test
    fun deviceInfo() {
        assertEquals(
            DeviceInfo("d1", 1_700_000_000_000L, isCurrent = true, revoked = false),
            im.g.gimcore.DeviceInfo("d1", 1_700_000_000_000UL, isCurrent = true, revoked = false).toMirror(),
        )
        assertEquals(
            DeviceInfo("d2", 0L, isCurrent = false, revoked = true),
            im.g.gimcore.DeviceInfo("d2", 0UL, isCurrent = false, revoked = true).toMirror(),
        )
        // u64 converts bit-for-bit.
        assertEquals(-1L, im.g.gimcore.DeviceInfo("d3", ULong.MAX_VALUE, false, false).toMirror().createdAtMs)
    }

    @Test
    fun hubInfo() {
        assertEquals(
            HubInfo("ha", "http://10.0.2.2:8080", HubState.VERIFIED, 0, isPrimary = true, isHome = true, signedIn = true),
            im.g.gimcore.HubInfo(
                "ha", "http://10.0.2.2:8080", im.g.gimcore.HubState.VERIFIED, 0u,
                isPrimary = true, isHome = true, signedIn = true,
            ).toMirror(),
        )
        assertEquals(
            HubInfo("hb", null, HubState.UNVERIFIED, 255, isPrimary = false, isHome = false, signedIn = false),
            im.g.gimcore.HubInfo(
                "hb", null, im.g.gimcore.HubState.UNVERIFIED, 255u,
                isPrimary = false, isHome = false, signedIn = false,
            ).toMirror(),
        )
        assertEquals(HubState.REMOVED, im.g.gimcore.HubState.REMOVED.toMirror())
    }

    @Test
    fun userProfile() {
        assertEquals(user, coreUser.toMirror())
    }

    @Test
    fun chatKind() {
        assertEquals(ChatKind.DIRECT, im.g.gimcore.ChatKind.DIRECT.toMirror())
        assertEquals(ChatKind.GROUP, im.g.gimcore.ChatKind.GROUP.toMirror())
    }

    @Test
    fun chatSummary() {
        assertEquals(summary, coreSummary.toMirror())
        assertEquals(
            summary.copy(avatar = null, lastMessage = null),
            coreSummary.copy(avatar = null, lastMessage = null).toMirror(),
        )
    }

    @Test
    fun messagePreview() {
        assertEquals(
            MessagePreview("Bob", "Photo", 5L, false),
            im.g.gimcore.MessagePreview("Bob", "Photo", 5UL, false).toMirror(),
        )
    }

    @Test
    fun mediaRef() {
        assertEquals(media, coreMedia.toMirror())
    }

    @Test
    fun unsignedValuesConvertBitForBit() {
        val max = coreSummary.copy(unreadCount = UInt.MAX_VALUE, updatedAtMs = ULong.MAX_VALUE).toMirror()
        assertEquals(-1, max.unreadCount)
        assertEquals(-1L, max.updatedAtMs)
    }

    @Test
    fun messageItem() {
        assertEquals(message, coreMessage.toMirror())
        assertEquals(
            message.copy(replyTo = null, reactions = emptyList(), sendState = null),
            coreMessage.copy(replyTo = null, reactions = emptyList(), sendState = null).toMirror(),
        )
    }

    @Test
    fun messageContent() {
        assertEquals(MessageContent.Text("b"), im.g.gimcore.MessageContent.Text("b").toMirror())
        assertEquals(
            MessageContent.Image(media, 640, 480, "cap"),
            im.g.gimcore.MessageContent.Image(coreMedia, 640u, 480u, "cap").toMirror(),
        )
        assertEquals(
            MessageContent.Image(media, null, null, null),
            im.g.gimcore.MessageContent.Image(coreMedia, null, null, null).toMirror(),
        )
        assertEquals(
            MessageContent.File(media, "report.pdf", "cap"),
            im.g.gimcore.MessageContent.File(coreMedia, "report.pdf", "cap").toMirror(),
        )
        assertEquals(
            MessageContent.File(media, null, null),
            im.g.gimcore.MessageContent.File(coreMedia, null, null).toMirror(),
        )
        assertSame(MessageContent.Deleted, im.g.gimcore.MessageContent.Deleted.toMirror())
        assertSame(MessageContent.Unsupported, im.g.gimcore.MessageContent.Unsupported.toMirror())
    }

    @Test
    fun replyPreviewAndReactionGroup() {
        assertEquals(ReplyPreview("m", "s", "t"), im.g.gimcore.ReplyPreview("m", "s", "t").toMirror())
        assertEquals(ReactionGroup("x", 7, false), im.g.gimcore.ReactionGroup("x", 7u, false).toMirror())
    }

    @Test
    fun sendState() {
        assertSame(SendState.Pending, im.g.gimcore.SendState.Pending.toMirror())
        assertSame(SendState.Sent, im.g.gimcore.SendState.Sent.toMirror())
        assertSame(SendState.Delivered, im.g.gimcore.SendState.Delivered.toMirror())
        assertSame(SendState.Read, im.g.gimcore.SendState.Read.toMirror())
        assertEquals(SendState.Failed("boom"), im.g.gimcore.SendState.Failed("boom").toMirror())
    }

    @Test
    fun connectionState() {
        assertEquals(ConnectionState.OFFLINE, im.g.gimcore.ConnectionState.OFFLINE.toMirror())
        assertEquals(ConnectionState.CONNECTING, im.g.gimcore.ConnectionState.CONNECTING.toMirror())
        assertEquals(ConnectionState.CONNECTED, im.g.gimcore.ConnectionState.CONNECTED.toMirror())
        assertEquals(ConnectionState.SYNCING, im.g.gimcore.ConnectionState.SYNCING.toMirror())
    }

    @Test
    fun timelineItem() {
        assertEquals(item, coreItem.toMirror())
        assertEquals(
            TimelineItem.DateSeparator(86_400_000L),
            im.g.gimcore.TimelineItem.DateSeparator(86_400_000UL).toMirror(),
        )
        assertEquals(
            TimelineItem.MembershipChange("Bob", MembershipChangeKind.LEFT, 9L, "u-bob", "u-bob"),
            im.g.gimcore.TimelineItem.MembershipChange("Bob", im.g.gimcore.MembershipChangeKind.LEFT, 9UL, "u-bob", "u-bob")
                .toMirror(),
        )
        assertEquals(
            TimelineItem.MembershipChange("Ana", MembershipChangeKind.REMOVED, 10L, "u-ana", null),
            im.g.gimcore.TimelineItem.MembershipChange("Ana", im.g.gimcore.MembershipChangeKind.REMOVED, 10UL, "u-ana", null)
                .toMirror(),
        )
    }

    @Test
    fun chatState() {
        assertEquals(ChatState.ACTIVE, im.g.gimcore.ChatState.ACTIVE.toMirror())
        assertEquals(ChatState.LEAVING, im.g.gimcore.ChatState.LEAVING.toMirror())
        assertEquals(ChatState.LEFT, im.g.gimcore.ChatState.LEFT.toMirror())
        for (state in ChatState.entries) assertEquals(state, state.toCore().toMirror())
    }

    @Test
    fun membershipChangeKind() {
        assertEquals(MembershipChangeKind.ADDED, im.g.gimcore.MembershipChangeKind.ADDED.toMirror())
        assertEquals(MembershipChangeKind.REMOVED, im.g.gimcore.MembershipChangeKind.REMOVED.toMirror())
        assertEquals(MembershipChangeKind.LEFT, im.g.gimcore.MembershipChangeKind.LEFT.toMirror())
    }

    @Test
    fun clientConfig() {
        assertEquals(im.g.gimcore.ClientConfig("https://gim.example"), ClientConfig("https://gim.example").toCore())
    }

    @Test
    fun chatListDiffs() {
        val cases = listOf(
            im.g.gimcore.ChatListDiff.Append(listOf(coreSummary)) to ChatListDiff.Append(listOf(summary)),
            im.g.gimcore.ChatListDiff.Clear to ChatListDiff.Clear,
            im.g.gimcore.ChatListDiff.PushFront(coreSummary) to ChatListDiff.PushFront(summary),
            im.g.gimcore.ChatListDiff.PushBack(coreSummary) to ChatListDiff.PushBack(summary),
            im.g.gimcore.ChatListDiff.PopFront to ChatListDiff.PopFront,
            im.g.gimcore.ChatListDiff.PopBack to ChatListDiff.PopBack,
            im.g.gimcore.ChatListDiff.Insert(2u, coreSummary) to ChatListDiff.Insert(2, summary),
            im.g.gimcore.ChatListDiff.Set(1u, coreSummary) to ChatListDiff.Set(1, summary),
            im.g.gimcore.ChatListDiff.Remove(3u) to ChatListDiff.Remove(3),
            im.g.gimcore.ChatListDiff.Truncate(4u) to ChatListDiff.Truncate(4),
            im.g.gimcore.ChatListDiff.Reset(listOf(coreSummary)) to ChatListDiff.Reset(listOf(summary)),
        )
        for ((core, mirror) in cases) assertEquals(mirror, core.toMirror())
    }

    @Test
    fun timelineDiffs() {
        val cases = listOf(
            im.g.gimcore.TimelineDiff.Append(listOf(coreItem)) to TimelineDiff.Append(listOf(item)),
            im.g.gimcore.TimelineDiff.Clear to TimelineDiff.Clear,
            im.g.gimcore.TimelineDiff.PushFront(coreItem) to TimelineDiff.PushFront(item),
            im.g.gimcore.TimelineDiff.PushBack(coreItem) to TimelineDiff.PushBack(item),
            im.g.gimcore.TimelineDiff.PopFront to TimelineDiff.PopFront,
            im.g.gimcore.TimelineDiff.PopBack to TimelineDiff.PopBack,
            im.g.gimcore.TimelineDiff.Insert(2u, coreItem) to TimelineDiff.Insert(2, item),
            im.g.gimcore.TimelineDiff.Set(1u, coreItem) to TimelineDiff.Set(1, item),
            im.g.gimcore.TimelineDiff.Remove(3u) to TimelineDiff.Remove(3),
            im.g.gimcore.TimelineDiff.Truncate(4u) to TimelineDiff.Truncate(4),
            im.g.gimcore.TimelineDiff.Reset(listOf(coreItem)) to TimelineDiff.Reset(listOf(item)),
        )
        for ((core, mirror) in cases) assertEquals(mirror, core.toMirror())
    }

    @Test
    fun errorsMapToMirror() {
        fun check(core: im.g.gimcore.GimException, expected: GimException) {
            val mirror = core.toMirror()
            assertEquals(expected.javaClass, mirror.javaClass)
            assertEquals(expected.message, mirror.message)
            assertSame(core, mirror.cause)
        }
        check(im.g.gimcore.GimException.Network("down"), GimException.Network("down"))
        check(im.g.gimcore.GimException.NotRegistered(), GimException.NotRegistered())
        check(im.g.gimcore.GimException.InvalidInput("bad"), GimException.InvalidInput("bad"))
        check(im.g.gimcore.GimException.NotFound("chat"), GimException.NotFound("chat"))
        check(im.g.gimcore.GimException.Crypto("mls"), GimException.Crypto("mls"))
        check(im.g.gimcore.GimException.Storage("disk"), GimException.Storage("disk"))
        check(im.g.gimcore.GimException.Server(503u, "busy"), GimException.Server(503, "busy"))
        check(
            im.g.gimcore.GimException.RetryLater(429u, 30UL, "rate_limited"),
            GimException.RetryLater(429, 30L, "rate_limited"),
        )
        check(im.g.gimcore.GimException.Cancelled(), GimException.Cancelled())
        check(im.g.gimcore.GimException.NotImplemented(), GimException.NotImplemented())
        check(im.g.gimcore.GimException.Internal("bug"), GimException.Internal("bug"))
        check(
            im.g.gimcore.GimException.ChatNotActive("c1", im.g.gimcore.ChatState.LEFT),
            GimException.ChatNotActive("c1", ChatState.LEFT),
        )
        check(im.g.gimcore.GimException.HubNotVerified("relay"), GimException.HubNotVerified("relay"))
        check(im.g.gimcore.GimException.HubOwnsGroups("hb", 2u), GimException.HubOwnsGroups("hb", 2))
        check(im.g.gimcore.GimException.LastHub("ha"), GimException.LastHub("ha"))
        check(im.g.gimcore.GimException.HomeHub("ha"), GimException.HomeHub("ha"))
        check(im.g.gimcore.GimException.HubNotDrained("hb", "1 message"), GimException.HubNotDrained("hb", "1 message"))
        val owns = im.g.gimcore.GimException.HubOwnsGroups("hb", 3u).toMirror() as GimException.HubOwnsGroups
        assertEquals("hb", owns.hubId)
        assertEquals(3, owns.groupCount)
        val notActive = im.g.gimcore.GimException.ChatNotActive("c1", im.g.gimcore.ChatState.LEAVING).toMirror()
        assertEquals("c1", (notActive as GimException.ChatNotActive).chatId)
        assertEquals(ChatState.LEAVING, notActive.state)
    }

    @Test
    fun errorsMapToCore() {
        fun check(mirror: GimException, expected: im.g.gimcore.GimException) {
            val core = mirror.toCore()
            assertEquals(expected.javaClass, core.javaClass)
            assertEquals(expected.message, core.message)
            assertSame(mirror, core.cause)
        }
        check(GimException.Network("down"), im.g.gimcore.GimException.Network("down"))
        check(GimException.NotRegistered(), im.g.gimcore.GimException.NotRegistered())
        check(GimException.InvalidInput("bad"), im.g.gimcore.GimException.InvalidInput("bad"))
        check(GimException.NotFound("chat"), im.g.gimcore.GimException.NotFound("chat"))
        check(GimException.Crypto("mls"), im.g.gimcore.GimException.Crypto("mls"))
        check(GimException.Storage("disk"), im.g.gimcore.GimException.Storage("disk"))
        check(GimException.Server(503, "busy"), im.g.gimcore.GimException.Server(503u, "busy"))
        check(
            GimException.RetryLater(503, 5L, "peer_unavailable"),
            im.g.gimcore.GimException.RetryLater(503u, 5UL, "peer_unavailable"),
        )
        check(GimException.Cancelled(), im.g.gimcore.GimException.Cancelled())
        check(GimException.NotImplemented(), im.g.gimcore.GimException.NotImplemented())
        check(GimException.Internal("bug"), im.g.gimcore.GimException.Internal("bug"))
        check(
            GimException.ChatNotActive("c1", ChatState.LEAVING),
            im.g.gimcore.GimException.ChatNotActive("c1", im.g.gimcore.ChatState.LEAVING),
        )
        check(GimException.HubNotVerified("relay"), im.g.gimcore.GimException.HubNotVerified("relay"))
        check(GimException.HubOwnsGroups("hb", 2), im.g.gimcore.GimException.HubOwnsGroups("hb", 2u))
        check(GimException.LastHub("ha"), im.g.gimcore.GimException.LastHub("ha"))
        check(GimException.HomeHub("ha"), im.g.gimcore.GimException.HomeHub("ha"))
        check(GimException.HubNotDrained("hb", "busy"), im.g.gimcore.GimException.HubNotDrained("hb", "busy"))
    }

    @Test
    fun retryLaterFieldsConvert() {
        val mirror = im.g.gimcore.GimException.RetryLater(429u, 60UL, "rate_limited").toMirror()
            as GimException.RetryLater
        assertEquals(429, mirror.status)
        assertEquals(60L, mirror.retryAfterSecs)
        assertEquals("rate_limited", mirror.reason)
        val core = mirror.toCore() as im.g.gimcore.GimException.RetryLater
        assertEquals(429.toUShort(), core.status)
        assertEquals(60UL, core.retryAfterSecs)
    }

    @Test
    fun paginationCount() {
        assertEquals(20.toUShort(), 20.toPaginationCount())
        assertEquals(UShort.MAX_VALUE, 1_000_000.toPaginationCount())
        assertTrue(runCatching { (-1).toPaginationCount() }.exceptionOrNull() is GimException.InvalidInput)
    }
}
