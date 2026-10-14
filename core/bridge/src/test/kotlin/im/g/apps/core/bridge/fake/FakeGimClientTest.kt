package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.LoginState
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.SendStatus
import im.g.apps.core.bridge.SystemEventKind
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.User
import im.g.apps.core.bridge.diff.ListDiff
import im.g.apps.core.bridge.diff.applyDiffs
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeGimClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class TimelineRecorder : TimelineListener {
        val diffs = mutableListOf<ListDiff<TimelineItem>>()
        var items = emptyList<TimelineItem>()

        override fun onUpdate(diffs: List<ListDiff<TimelineItem>>) {
            this.diffs += diffs
            items = items.applyDiffs(diffs)
        }

        fun message(index: Int) = items[index] as TimelineItem.Message
    }

    private class ChatListRecorder : ChatListListener {
        val diffs = mutableListOf<ListDiff<ChatSummary>>()
        var items = emptyList<ChatSummary>()

        override fun onUpdate(diffs: List<ListDiff<ChatSummary>>) {
            this.diffs += diffs
            items = items.applyDiffs(diffs)
        }
    }

    private fun TestScope.demo(manualOutbox: Boolean = true) = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        cacheDir = tmp.newFolder(),
        outboxDelays = if (manualOutbox) null else FakeGimClient.OutboxDelays(100, 200, 300),
    )

    private inline fun <reified E : GimException> assertGimError(block: () -> Unit): E {
        try {
            block()
        } catch (e: GimException) {
            if (e is E) return e
            fail("expected ${E::class.simpleName} but got $e")
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun register_validates_input_and_updates_login_state() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { 0L }, cacheDir = tmp.newFolder())
        assertEquals(LoginState.Unregistered, client.loginState())

        val blank = assertGimError<GimException.InvalidInput> { client.register("https://gim.example.eu", "  ", "Marta") }
        assertEquals("handle", blank.field)
        assertEquals(
            "serverUrl",
            assertGimError<GimException.InvalidInput> { client.register("gim.example.eu", "marta", "Marta") }.field,
        )
        assertEquals(
            "displayName",
            assertGimError<GimException.InvalidInput> { client.register("https://gim.example.eu", "marta", " ") }.field,
        )

        val session = client.register("https://gim.example.eu", "@Marta", "Marta Rossi")
        assertEquals("marta", session.handle)
        assertEquals(LoginState.Registered(session), client.loginState())
        assertGimError<GimException.Conflict> { client.register("https://gim.example.eu", "other", "Other") }
    }

    @Test
    fun operations_require_registration() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { 0L }, cacheDir = tmp.newFolder())
        assertGimError<GimException.Unauthorized> { client.findUser("ana") }
        assertGimError<GimException.Unauthorized> { client.start() }
    }

    @Test
    fun find_user_searches_directory() = runTest {
        val client = demo()
        assertEquals(DemoData.ana, client.findUser("@Ana "))
        assertNull(client.findUser("nobody"))
    }

    @Test
    fun send_text_goes_pending_sent_delivered_read() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(DIRECT_CHAT_ID, timeline)
        timeline.diffs.clear()

        val id = client.sendText(DIRECT_CHAT_ID, "Fair winds", replyTo = null)

        assertEquals(ListDiff.PushFront::class, timeline.diffs.single()::class)
        assertEquals(id, timeline.message(0).id)
        assertEquals(SendStatus.PENDING, timeline.message(0).status)
        assertEquals(MessageContent.Text("Fair winds"), timeline.message(0).content)

        for (expected in listOf(SendStatus.SENT, SendStatus.DELIVERED, SendStatus.READ)) {
            timeline.diffs.clear()
            client.advanceOutbox()
            val diff = timeline.diffs.single() as ListDiff.Set
            assertEquals(0, diff.index)
            assertEquals(expected, (diff.item as TimelineItem.Message).status)
        }
        timeline.diffs.clear()
        client.advanceOutbox()
        assertTrue(timeline.diffs.isEmpty())
    }

    @Test
    fun send_lifecycle_follows_configured_delays() = runTest {
        val client = demo(manualOutbox = false)
        val timeline = TimelineRecorder()
        client.subscribeTimeline(DIRECT_CHAT_ID, timeline)

        client.sendText(DIRECT_CHAT_ID, "On my way", replyTo = null)
        runCurrent()
        assertEquals(SendStatus.PENDING, timeline.message(0).status)
        advanceTimeBy(101)
        assertEquals(SendStatus.SENT, timeline.message(0).status)
        advanceTimeBy(200)
        assertEquals(SendStatus.DELIVERED, timeline.message(0).status)
        advanceTimeBy(300)
        assertEquals(SendStatus.READ, timeline.message(0).status)
    }

    @Test
    fun failed_send_recovers_on_retry() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(GROUP_CHAT_ID, timeline)

        client.failNextSend()
        val id = client.sendText(GROUP_CHAT_ID, "Anchored in Kea", replyTo = null)
        client.advanceOutbox()
        assertEquals(SendStatus.FAILED, timeline.message(0).status)
        client.advanceOutbox()
        assertEquals(SendStatus.FAILED, timeline.message(0).status)

        client.retrySend(id)
        assertEquals(SendStatus.PENDING, timeline.message(0).status)
        client.advanceOutbox()
        assertEquals(SendStatus.SENT, timeline.message(0).status)
        assertGimError<GimException.Conflict> { client.retrySend(id) }
    }

    @Test
    fun send_with_reply_includes_preview() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(DIRECT_CHAT_ID, timeline)
        val target = timeline.message(1)

        client.sendText(DIRECT_CHAT_ID, "Agreed", replyTo = target.id)

        val reply = timeline.message(0).replyTo!!
        assertEquals(target.id, reply.messageId)
        assertEquals(target.sender.displayName, reply.senderName)
        assertGimError<GimException.NotFound> { client.sendText(DIRECT_CHAT_ID, "x", replyTo = "missing") }
        assertGimError<GimException.InvalidInput> { client.sendText(DIRECT_CHAT_ID, " ", replyTo = null) }
    }

    @Test
    fun send_media_maps_mime_to_content_type() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(DIRECT_CHAT_ID, timeline)
        val photo = tmp.newFile("harbour.jpg").apply { writeBytes(ByteArray(10)) }
        val doc = tmp.newFile("plan.pdf").apply { writeBytes(ByteArray(20)) }

        client.sendMedia(DIRECT_CHAT_ID, photo.path, "image/jpeg", "harbour.jpg", "Harbour", null)
        val image = timeline.message(0).content as MessageContent.Image
        assertEquals("Harbour", image.caption)
        assertEquals(10L, image.size)

        client.sendMedia(DIRECT_CHAT_ID, doc.path, "application/pdf", "plan.pdf", null, null)
        val file = timeline.message(0).content as MessageContent.File
        assertEquals("plan.pdf", file.fileName)
        assertEquals(20L, file.size)
        assertEquals(doc.readBytes().size, File(client.fetchMedia(file.mediaId)).readBytes().size)

        assertGimError<GimException.InvalidInput> {
            client.sendMedia(DIRECT_CHAT_ID, "/missing/file", "image/png", "f.png", null, null)
        }
    }

    @Test
    fun edit_delete_and_reactions_emit_set_diffs() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(DIRECT_CHAT_ID, timeline)
        val id = client.sendText(DIRECT_CHAT_ID, "Draft", replyTo = null)

        timeline.diffs.clear()
        client.editMessage(id, "Final")
        val edited = (timeline.diffs.single() as ListDiff.Set).item as TimelineItem.Message
        assertEquals(MessageContent.Text("Final"), edited.content)
        assertTrue(edited.editedAt != null)

        timeline.diffs.clear()
        client.toggleReaction(id, "⛵")
        assertEquals(listOf(ReactionGroup("⛵", 1, true)), (timeline.diffs.single() as ListDiff.Set).let { (it.item as TimelineItem.Message).reactions })
        client.toggleReaction(id, "⛵")
        assertEquals(emptyList<ReactionGroup>(), timeline.message(0).reactions)

        timeline.diffs.clear()
        client.deleteMessage(id)
        val deleted = (timeline.diffs.single() as ListDiff.Set).item as TimelineItem.Message
        assertTrue(deleted.deleted)
        assertEquals(MessageContent.Deleted, deleted.content)
        assertGimError<GimException.InvalidInput> { client.editMessage(id, "again") }
    }

    @Test
    fun reaction_on_others_message_joins_existing_group() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(GROUP_CHAT_ID, timeline)
        val index = timeline.items.indexOfFirst { (it as? TimelineItem.Message)?.reactions?.any { r -> r.emoji == "😍" } == true }
        val target = timeline.message(index)

        client.toggleReaction(target.id, "😍")
        assertEquals(listOf(ReactionGroup("😍", 3, true)), timeline.message(index).reactions)
        assertGimError<GimException.InvalidInput> { client.editMessage(target.id, "hijack") }
        assertGimError<GimException.InvalidInput> { client.deleteMessage(target.id) }
    }

    @Test
    fun paginate_backwards_loads_older_pages_until_end() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        client.subscribeTimeline(GROUP_CHAT_ID, timeline)
        assertEquals(FakeGimClient.DEFAULT_PAGE_SIZE, timeline.items.size)
        val newest = timeline.items.first()
        timeline.diffs.clear()

        assertTrue(client.paginateBackwards(GROUP_CHAT_ID, 15))
        assertEquals(35, timeline.items.size)
        assertTrue(timeline.diffs.all { it is ListDiff.PushBack })
        assertEquals(newest, timeline.items.first())

        assertFalse(client.paginateBackwards(GROUP_CHAT_ID, 15))
        assertEquals(40, timeline.items.size)
        val oldest = timeline.items.last() as TimelineItem.System
        assertEquals(SystemEventKind.CHAT_CREATED, oldest.kind)

        timeline.diffs.clear()
        assertFalse(client.paginateBackwards(GROUP_CHAT_ID, 15))
        assertTrue(timeline.diffs.isEmpty())
    }

    @Test
    fun demo_data_is_ordered_newest_first_with_variety() = runTest {
        val client = demo()
        val chats = ChatListRecorder()
        client.subscribeChatList(chats)
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), chats.items.map { it.id })
        assertEquals(listOf(3, 0), chats.items.map { it.unreadCount })
        assertEquals("Lukas Berger", chats.items[1].name)
        assertEquals(ChatKind.GROUP, chats.items[0].kind)

        val all = mutableListOf<TimelineItem>()
        for (chatId in listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID)) {
            val timeline = TimelineRecorder()
            client.subscribeTimeline(chatId, timeline)
            while (client.paginateBackwards(chatId, 50)) Unit
            assertEquals(timeline.items.sortedByDescending { it.time() }, timeline.items)
            all += timeline.items
        }
        val messages = all.filterIsInstance<TimelineItem.Message>()
        assertEquals(60, all.size)
        assertTrue(messages.any { it.content is MessageContent.Image })
        assertTrue(messages.any { it.content is MessageContent.File })
        assertTrue(messages.any { it.replyTo != null })
        assertTrue(messages.any { it.reactions.isNotEmpty() })
        assertTrue(messages.any { it.editedAt != null })
        assertTrue(messages.any { it.deleted })
        assertTrue(all.any { it is TimelineItem.System })
    }

    @Test
    fun chat_list_reorders_on_incoming_and_mark_read_clears_unread() = runTest {
        val client = demo()
        val chats = ChatListRecorder()
        client.subscribeChatList(chats)
        chats.diffs.clear()

        advanceTimeBy(1_000)
        val id = client.simulateIncoming(DIRECT_CHAT_ID, "Weather window looks good", from = DemoData.lukas.id)

        assertEquals(ListDiff.Remove(1), chats.diffs[0])
        assertEquals(0, (chats.diffs[1] as ListDiff.Insert).index)
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), chats.items.map { it.id })
        val direct = chats.items[0]
        assertEquals(1, direct.unreadCount)
        assertEquals(id, direct.lastMessage?.messageId)
        assertEquals(1_000L, direct.lastActivityAt)

        chats.diffs.clear()
        client.markRead(DIRECT_CHAT_ID, id)
        assertEquals(0, (chats.diffs.single() as ListDiff.Set).index)
        assertEquals(0, chats.items[0].unreadCount)
        assertEquals(0, client.chatDetails(DIRECT_CHAT_ID).unreadCount)
    }

    @Test
    fun outgoing_message_moves_chat_to_top_without_unread() = runTest {
        val client = demo()
        val chats = ChatListRecorder()
        client.subscribeChatList(chats)

        advanceTimeBy(1_000)
        client.sendText(DIRECT_CHAT_ID, "Ready", replyTo = null)
        assertEquals(DIRECT_CHAT_ID, chats.items[0].id)
        assertEquals(0, chats.items[0].unreadCount)
        assertEquals(SendStatus.PENDING, chats.items[0].lastMessage?.status)
    }

    @Test
    fun create_direct_and_group_chats() = runTest {
        val client = demo()
        val chats = ChatListRecorder()
        client.subscribeChatList(chats)
        chats.diffs.clear()

        assertEquals(DIRECT_CHAT_ID, client.createDirectChat(DemoData.lukas.id))
        assertTrue(chats.diffs.isEmpty())

        advanceTimeBy(1_000)
        val direct = client.createDirectChat(DemoData.ana.id)
        assertEquals(ListDiff.Insert(0, chats.items[0]), chats.diffs.single())
        assertEquals("Ana Kovač", chats.items[0].name)
        assertGimError<GimException.NotFound> { client.createDirectChat("u-nobody") }
        assertGimError<GimException.InvalidInput> { client.createDirectChat(DemoData.me.id) }

        advanceTimeBy(1_000)
        val group = client.createGroupChat("Baltic Regatta", listOf(DemoData.ana.id, DemoData.nikos.id))
        assertNotEquals(direct, group)
        val summary = client.chatDetails(group)
        assertEquals(ChatKind.GROUP, summary.kind)
        assertEquals(listOf(DemoData.me, DemoData.ana, DemoData.nikos), summary.members)
        assertEquals(group, chats.items[0].id)
        assertGimError<GimException.InvalidInput> { client.createGroupChat(" ", emptyList()) }
    }

    @Test
    fun add_and_remove_members_update_members_and_timeline() = runTest {
        val client = demo()
        val group = client.createGroupChat("North Sea", listOf(DemoData.ana.id))
        val timeline = TimelineRecorder()
        client.subscribeTimeline(group, timeline)

        client.addMember(group, DemoData.lukas.id)
        assertTrue(DemoData.lukas in client.chatDetails(group).members)
        assertEquals(SystemEventKind.MEMBER_ADDED, (timeline.items[0] as TimelineItem.System).kind)
        assertGimError<GimException.Conflict> { client.addMember(group, DemoData.lukas.id) }

        client.removeMember(group, DemoData.ana.id)
        assertFalse(DemoData.ana in client.chatDetails(group).members)
        assertEquals(SystemEventKind.MEMBER_REMOVED, (timeline.items[0] as TimelineItem.System).kind)
        assertGimError<GimException.NotFound> { client.removeMember(group, DemoData.ana.id) }
        assertGimError<GimException.InvalidInput> { client.addMember(DIRECT_CHAT_ID, DemoData.ana.id) }
    }

    @Test
    fun typing_and_connection_listeners() = runTest {
        val client = demo()
        val typing = mutableListOf<Pair<String, List<User>>>()
        client.subscribeTyping { chatId, users -> typing += chatId to users }
        val states = mutableListOf<ConnectionState>()
        client.subscribeConnection { states += it }

        client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.id, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, DemoData.nikos.id, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.id, typing = false)
        assertEquals(
            listOf(
                GROUP_CHAT_ID to listOf(DemoData.ana),
                GROUP_CHAT_ID to listOf(DemoData.ana, DemoData.nikos),
                GROUP_CHAT_ID to listOf(DemoData.nikos),
            ),
            typing,
        )
        val late = mutableListOf<List<User>>()
        client.subscribeTyping { _, users -> late += users }
        assertEquals(listOf(listOf(DemoData.nikos)), late)

        client.setTyping(GROUP_CHAT_ID, true)
        assertTrue(client.isTyping(GROUP_CHAT_ID))

        client.start()
        client.setConnection(ConnectionState.CONNECTING)
        client.stop()
        assertEquals(
            listOf(
                ConnectionState.OFFLINE,
                ConnectionState.CONNECTING,
                ConnectionState.ONLINE,
                ConnectionState.CONNECTING,
                ConnectionState.OFFLINE,
            ),
            states,
        )
    }

    @Test
    fun fetch_media_writes_placeholder_png() = runTest {
        val client = demo()
        val path = client.fetchMedia("media-demo-1")
        val bytes = File(path).readBytes()
        assertTrue(File(path).isAbsolute)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF })
        assertTrue(File(client.fetchMedia("media-demo-4")).length() > 0)
        assertGimError<GimException.NotFound> { client.fetchMedia("media-unknown") }
    }

    @Test
    fun cancelled_subscription_stops_updates() = runTest {
        val client = demo()
        val timeline = TimelineRecorder()
        val subscription = client.subscribeTimeline(DIRECT_CHAT_ID, timeline)
        assertEquals(1, client.activeSubscriptions)

        subscription.cancel()
        subscription.cancel()
        timeline.diffs.clear()
        client.simulateIncoming(DIRECT_CHAT_ID, "Hello?", from = DemoData.lukas.id)
        assertTrue(timeline.diffs.isEmpty())
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun logout_clears_state() = runTest {
        val client = demo()
        val chats = ChatListRecorder()
        client.subscribeChatList(chats)
        client.logout()
        assertEquals(LoginState.Unregistered, client.loginState())
        assertTrue(chats.items.isEmpty())
    }

    private fun TimelineItem.time() = when (this) {
        is TimelineItem.Message -> sentAt
        is TimelineItem.System -> at
    }
}
