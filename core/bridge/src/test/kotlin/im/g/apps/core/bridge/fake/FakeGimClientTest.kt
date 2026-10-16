package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimException
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
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.applyDiffs
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.ana
import im.g.apps.core.bridge.fake.DemoData.lukas
import im.g.apps.core.bridge.fake.DemoData.me
import im.g.apps.core.bridge.fake.DemoData.nikos
import im.g.apps.core.bridge.key
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class FakeGimClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Athens")
    private val base = ZonedDateTime.of(2026, 7, 12, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun dayStart(year: Int, month: Int, day: Int) =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    private class TimelineRecorder : TimelineListener {
        val batches = mutableListOf<List<TimelineDiff>>()
        val items = mutableListOf<TimelineItem>()

        val messages: List<MessageItem>
            get() = items.filterIsInstance<TimelineItem.Message>().map { it.item }

        override fun onUpdate(diffs: List<TimelineDiff>) {
            batches += diffs
            items.applyDiffs(diffs)
        }

        fun last() = messages.last()
    }

    private class ChatListRecorder : ChatListListener {
        val batches = mutableListOf<List<ChatListDiff>>()
        val items = mutableListOf<ChatSummary>()

        val ids: List<String>
            get() = items.map { it.chatId }

        override fun onUpdate(diffs: List<ChatListDiff>) {
            batches += diffs
            items.applyDiffs(diffs)
        }

        fun chat(chatId: String) = items.single { it.chatId == chatId }
    }

    private fun TestScope.demo(manualOutbox: Boolean = true) = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { base + testScheduler.currentTime },
        zone = zone,
        outboxDelays = if (manualOutbox) null else FakeGimClient.OutboxDelays(100, 200, 300),
    )

    private suspend fun FakeGimClient.record(chatId: String) =
        TimelineRecorder().also { timeline(chatId).addListener(it) }

    private suspend fun FakeGimClient.recordChats() = ChatListRecorder().also { chatList(it) }

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
    fun create_identity_claim_handle_and_sign_out() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        assertNull(client.session())
        assertNull(client.userId())
        assertFalse(client.localStateReset())
        assertGimError<GimException.NotRegistered> { client.findUser("lukas") }
        assertGimError<GimException.NotRegistered> { client.claimHandle("marta") }
        assertGimError<GimException.InvalidInput> { client.createIdentity(" ") }

        val code = client.createIdentity(" Marta Rossi ")
        assertEquals(FakeGimClient.fakeRecoveryCode(1), code)
        assertTrue(code.matches(Regex("([0-9A-HJKMNP-TV-Z]{4}-){9}[0-9A-HJKMNP-TV-Z]{3}")))
        val session = client.session()!!
        assertEquals("", session.handle)
        assertEquals("Marta Rossi", session.displayName)
        assertEquals(session.userId, client.userId())
        assertEquals(listOf(DeviceInfo(session.deviceId, base, isCurrent = true, revoked = false)), client.devices())
        assertGimError<GimException.InvalidInput> { client.createIdentity("Other") }
        assertGimError<GimException.InvalidInput> { client.releaseHandle() }

        assertGimError<GimException.InvalidInput> { client.claimHandle("x") }
        client.addDirectoryUser(lukas)
        val taken = assertGimError<GimException.Server> { client.claimHandle("lukas") }
        assertEquals(409, taken.status)
        client.claimHandle(" @Marta ")
        assertEquals("marta", client.session()?.handle)
        client.claimHandle("marta") // own handle again: no conflict
        client.setDisplayName("Marta R.")
        assertGimError<GimException.InvalidInput> { client.setDisplayName("  ") }
        assertEquals(Session(session.userId, session.deviceId, "marta", "Marta R."), client.session())

        val chats = client.recordChats()
        val chatId = client.createDirectChat(lukas.userId)
        assertEquals(listOf(chatId), chats.ids)
        client.stopTyping(chatId)
        assertFalse(client.isTyping(chatId))

        client.releaseHandle()
        assertEquals("", client.session()?.handle)

        client.signOut()
        assertNull(client.session())
        assertNull(client.userId())
        assertEquals(listOf<ChatListDiff>(ChatListDiff.Clear), chats.batches.last())
        assertTrue(chats.items.isEmpty())
        assertGimError<GimException.NotRegistered> { client.timeline(chatId) }
        assertGimError<GimException.NotRegistered> { client.signOut() }
        assertGimError<GimException.NotRegistered> { client.devices() }

        client.createIdentity("Sofia Costa")
        client.claimHandle("sofia")
        assertEquals("sofia", client.session()?.handle)
    }

    @Test
    fun local_state_reset_reports_the_constructor_flag() {
        assertTrue(FakeGimClient(TestScope(), discardedLocalState = true).localStateReset())
    }

    @Test
    fun devices_revoke_and_user_devices() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        val current = client.session()!!.deviceId
        assertGimError<GimException.InvalidInput> { client.revokeDevice(current) }
        assertGimError<GimException.NotFound> { client.revokeDevice("d-unknown") }

        val approval = client.approveLink(peerLinkQr())
        client.confirmApproval(approval, accepted = true)
        assertEquals(
            listOf(
                DeviceInfo(current, base, isCurrent = true, revoked = false),
                DeviceInfo(approval.deviceId(), base, isCurrent = false, revoked = false),
            ),
            client.devices(),
        )
        client.revokeDevice(approval.deviceId())
        assertEquals(DeviceInfo(approval.deviceId(), 0, isCurrent = false, revoked = true), client.devices().last())

        assertEquals(listOf(DeviceInfo("d-${lukas.userId}", 0, false, false)), client.userDevices(lukas.userId))
        assertEquals(emptyList<DeviceInfo>(), client.userDevices("u-nobody"))
    }

    @Test
    fun link_flow_new_device_signs_in_after_confirm() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        client.linkedUser = UserProfile("u-marta", "marta", "Marta Rossi")
        assertGimError<GimException.InvalidInput> { client.confirmLink(true) }

        val link = client.startLink(listOf("https://hub.example.eu"))
        assertTrue(link.qrString().isNotEmpty())
        val sas = client.waitLink(link)
        assertTrue(sas.matches(Regex("\\d{3} \\d{3}")))
        assertEquals("u-marta", client.userId())
        assertGimError<GimException.InvalidInput> { client.waitLink(link) }

        // The old device sees the same SAS for the same QR code.
        val old = FakeGimClient.withDemoData(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        val approval = old.approveLink(link.qrString())
        assertEquals(sas, approval.sas())
        assertGimError<GimException.InvalidInput> { old.approveLink("not a link") }
        old.confirmApproval(approval, accepted = false)
        assertTrue(old.devices().last().revoked)
        assertGimError<GimException.InvalidInput> { old.confirmApproval(approval, accepted = true) }

        client.confirmLink(true)
        assertEquals("marta", client.session()?.handle)
    }

    @Test
    fun rejected_link_wipes_the_new_device() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        client.waitLink(client.startLink(emptyList()))
        client.confirmLink(false)
        assertNull(client.session())
        assertGimError<GimException.InvalidInput> { client.confirmLink(false) }
    }

    @Test
    fun restore_from_recovery_validates_code_and_revokes_others() = runTest {
        val client = FakeGimClient(backgroundScope, clock = { base }, zone = zone, outboxDelays = null)
        val marta = UserProfile("u-marta", "marta", "Marta Rossi")
        val code = FakeGimClient.fakeRecoveryCode(7)
        client.addRecoverableUser(marta, code, listOf(DeviceInfo("d-old", 1L, isCurrent = false, revoked = false)))

        assertGimError<GimException.InvalidInput> { client.restoreFromRecovery(marta.userId, "ABC", true) }
        assertGimError<GimException.NotFound> { client.restoreFromRecovery("u-nobody", code, true) }
        assertGimError<GimException.Crypto> {
            client.restoreFromRecovery(marta.userId, FakeGimClient.fakeRecoveryCode(8), true)
        }

        // Case, dashes and whitespace do not matter.
        client.restoreFromRecovery(marta.userId, code.lowercase().replace("-", " "), revokeOthers = true)
        val session = client.session()!!
        assertEquals(marta.userId, session.userId)
        assertEquals("marta", session.handle)
        assertEquals(
            listOf(
                DeviceInfo("d-old", 0, isCurrent = false, revoked = true),
                DeviceInfo(session.deviceId, base, isCurrent = true, revoked = false),
            ),
            client.devices(),
        )
        assertGimError<GimException.InvalidInput> { client.restoreFromRecovery(marta.userId, code, false) }
    }

    @Test
    fun send_text_appends_local_echo_and_advances_send_state_manually() = runTest {
        val client = demo()
        val timeline = client.record(DIRECT_CHAT_ID)
        val before = timeline.items.size

        val id = client.sendText(DIRECT_CHAT_ID, "Lines are coiled", replyTo = null)
        val push = timeline.batches.last().single() as TimelineDiff.PushBack
        assertEquals(id, push.value.key)
        assertEquals(before + 1, timeline.items.size)
        assertEquals(SendState.Pending, timeline.last().sendState)
        assertTrue(timeline.last().isOwn)

        for (expected in listOf(SendState.Sent, SendState.Delivered, SendState.Read)) {
            client.advanceOutbox()
            val set = timeline.batches.last().single() as TimelineDiff.Set
            assertEquals(timeline.items.lastIndex, set.index)
            assertEquals(expected, timeline.last().sendState)
        }
        val batches = timeline.batches.size
        client.advanceOutbox()
        assertEquals(batches, timeline.batches.size)
    }

    @Test
    fun send_state_advances_with_outbox_delays() = runTest {
        val client = demo(manualOutbox = false)
        val timeline = client.record(DIRECT_CHAT_ID)
        client.sendText(DIRECT_CHAT_ID, "On my way", replyTo = null)

        advanceTimeBy(101)
        assertEquals(SendState.Sent, timeline.last().sendState)
        advanceTimeBy(200)
        assertEquals(SendState.Delivered, timeline.last().sendState)
        advanceTimeBy(300)
        assertEquals(SendState.Read, timeline.last().sendState)
    }

    @Test
    fun reply_carries_a_preview_of_the_quoted_message() = runTest {
        val client = demo()
        val timeline = client.record(DIRECT_CHAT_ID)
        val survey = timeline.messages.single { it.content is MessageContent.File }

        client.sendText(DIRECT_CHAT_ID, "Read it, all good", replyTo = survey.messageId)
        assertEquals(ReplyPreview(survey.messageId, lukas.displayName, "File: survey-2026.pdf"), timeline.last().replyTo)
        assertGimError<GimException.NotFound> { client.sendText(DIRECT_CHAT_ID, "x", replyTo = "missing") }
        assertGimError<GimException.InvalidInput> { client.sendText(DIRECT_CHAT_ID, " ", replyTo = null) }
    }

    @Test
    fun failed_send_can_be_retried_or_cancelled() = runTest {
        val client = demo()
        val timeline = client.record(DIRECT_CHAT_ID)

        client.failNextSend("Server unreachable")
        val id = client.sendText(DIRECT_CHAT_ID, "Weather window looks good", replyTo = null)
        client.advanceOutbox()
        assertEquals(SendState.Failed("Server unreachable"), timeline.last().sendState)

        client.retrySend(DIRECT_CHAT_ID, id)
        assertEquals(SendState.Pending, timeline.last().sendState)
        client.advanceOutbox()
        assertEquals(SendState.Sent, timeline.last().sendState)
        assertGimError<GimException.InvalidInput> { client.retrySend(DIRECT_CHAT_ID, id) }
        assertGimError<GimException.InvalidInput> { client.cancelSend(DIRECT_CHAT_ID, id) }

        client.failNextSend()
        val doomed = client.sendText(DIRECT_CHAT_ID, "Typo", replyTo = null)
        client.advanceOutbox()
        assertTrue(timeline.last().sendState is SendState.Failed)
        val index = timeline.items.lastIndex
        client.cancelSend(DIRECT_CHAT_ID, doomed)
        assertEquals(listOf<TimelineDiff>(TimelineDiff.Remove(index)), timeline.batches.last())
        assertTrue(timeline.messages.none { it.messageId == doomed })
        assertEquals(timeline.items, client.record(DIRECT_CHAT_ID).items)
    }

    @Test
    fun edit_delete_react_and_unreact_emit_set_diffs() = runTest {
        val client = demo()
        val timeline = client.record(DIRECT_CHAT_ID)
        val own = timeline.messages.last { it.isOwn }
        val theirs = timeline.messages.last { !it.isOwn }
        val ownIndex = timeline.items.indexOfFirst { it.key == own.messageId }
        val theirIndex = timeline.items.indexOfFirst { it.key == theirs.messageId }

        client.editMessage(DIRECT_CHAT_ID, own.messageId, "See you Thursday!")
        val edit = timeline.batches.last().single() as TimelineDiff.Set
        assertEquals(ownIndex, edit.index)
        val edited = (edit.value as TimelineItem.Message).item
        assertEquals(MessageContent.Text("See you Thursday!"), edited.content)
        assertTrue(edited.edited)
        assertGimError<GimException.InvalidInput> { client.editMessage(DIRECT_CHAT_ID, theirs.messageId, "x") }
        assertGimError<GimException.InvalidInput> { client.deleteMessage(DIRECT_CHAT_ID, theirs.messageId) }
        assertGimError<GimException.NotFound> { client.editMessage(DIRECT_CHAT_ID, "missing", "x") }

        client.react(DIRECT_CHAT_ID, theirs.messageId, "⛵")
        assertEquals(theirIndex, (timeline.batches.last().single() as TimelineDiff.Set).index)
        assertEquals(listOf(ReactionGroup("⛵", 1, own = true)), timeline.items.message(theirIndex).reactions)
        val batches = timeline.batches.size
        client.react(DIRECT_CHAT_ID, theirs.messageId, "⛵")
        assertEquals(batches, timeline.batches.size)

        val liked = timeline.messages.single { m -> m.reactions.any { it.emoji == "👍" } }
        client.react(DIRECT_CHAT_ID, liked.messageId, "👍")
        assertEquals(listOf(ReactionGroup("👍", 2, own = true)), timeline.messages.single { it.messageId == liked.messageId }.reactions)
        client.unreact(DIRECT_CHAT_ID, liked.messageId, "👍")
        assertEquals(listOf(ReactionGroup("👍", 1, own = false)), timeline.messages.single { it.messageId == liked.messageId }.reactions)
        client.unreact(DIRECT_CHAT_ID, theirs.messageId, "⛵")
        assertEquals(emptyList<ReactionGroup>(), timeline.items.message(theirIndex).reactions)

        client.deleteMessage(DIRECT_CHAT_ID, own.messageId)
        val delete = timeline.batches.last().single() as TimelineDiff.Set
        assertEquals(ownIndex, delete.index)
        assertEquals(MessageContent.Deleted, timeline.items.message(ownIndex).content)
        assertGimError<GimException.InvalidInput> { client.react(DIRECT_CHAT_ID, own.messageId, "👍") }
    }

    /** A QR string as another fake's [FakeGimClient.startLink] returns it. */
    private fun peerLinkQr(): String = FakeGimClient(TestScope()).startLink(emptyList()).qrString()

    private fun List<TimelineItem>.message(index: Int) = (this[index] as TimelineItem.Message).item

    @Test
    fun date_separators_precede_the_first_item_of_each_local_day() = runTest {
        var now = ZonedDateTime.of(2026, 7, 11, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        val client = FakeGimClient(backgroundScope, clock = { now }, zone = zone, outboxDelays = null)
        client.createIdentity("Sofia Costa")
        client.addDirectoryUser(lukas)
        val chatId = client.createDirectChat(lukas.userId)
        val timeline = client.record(chatId)
        assertEquals(listOf<TimelineDiff>(TimelineDiff.Reset(emptyList())), timeline.batches.single())

        val first = client.sendText(chatId, "Leaving Split tonight", replyTo = null)
        val append = timeline.batches.last().single() as TimelineDiff.Append
        assertEquals(listOf("date-${dayStart(2026, 7, 11)}", first), append.values.map { it.key })

        now += 5 * 60_000
        val second = client.sendText(chatId, "Engine on", replyTo = null)
        assertTrue(timeline.batches.last().single() is TimelineDiff.PushBack)

        now += 10 * 60_000 // 00:05 the next day in Athens
        val incoming = client.simulateIncoming(chatId, "Fair winds", from = lukas.userId)
        assertTrue(timeline.batches.last().single() is TimelineDiff.Append)

        val day11 = dayStart(2026, 7, 11)
        val day12 = dayStart(2026, 7, 12)
        assertEquals(listOf("date-$day11", first, second, "date-$day12", incoming), timeline.items.map { it.key })
        assertEquals(TimelineItem.DateSeparator(day12), timeline.items[3])
    }

    @Test
    fun demo_timeline_spans_several_days_oldest_first() = runTest {
        val client = demo()
        val items = client.record(DIRECT_CHAT_ID).items
        assertTrue(items.first() is TimelineItem.DateSeparator)
        val separators = items.filterIsInstance<TimelineItem.DateSeparator>()
        assertEquals(listOf(dayStart(2026, 7, 10), dayStart(2026, 7, 11), dayStart(2026, 7, 12)), separators.map { it.dayStartMs })
        val times = items.filterIsInstance<TimelineItem.Message>().map { it.item.sentAtMs }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun pagination_prepends_older_items_until_history_ends() = runTest {
        val client = demo()
        val recorder = client.record(GROUP_CHAT_ID)
        fun events() = recorder.items.filter { it !is TimelineItem.DateSeparator }
        assertEquals(FakeGimClient.DEFAULT_PAGE_SIZE, events().size)
        val newest = recorder.items.last()

        val timeline = client.timeline(GROUP_CHAT_ID)
        assertEquals(GROUP_CHAT_ID, timeline.chatId())
        assertTrue(timeline.paginateBackwards(10))
        assertEquals(30, events().size)
        val page = recorder.batches.last()
        assertEquals(10, page.size)
        assertTrue(page.all { it is TimelineDiff.Insert || it is TimelineDiff.PushFront })

        assertFalse(timeline.paginateBackwards(100))
        val oldest = recorder.items.subList(1, 3).map { it as TimelineItem.MembershipChange }
        assertEquals(listOf(lukas.displayName, nikos.displayName), oldest.map { it.userName })
        assertTrue(oldest.all { it.change == MembershipChangeKind.ADDED })
        assertTrue(recorder.items.first() is TimelineItem.DateSeparator)
        assertEquals(newest, recorder.items.last())

        val batches = recorder.batches.size
        assertFalse(timeline.paginateBackwards(5))
        assertEquals(batches, recorder.batches.size)
        assertGimError<GimException.InvalidInput> { timeline.paginateBackwards(0) }
        assertEquals(recorder.items, client.record(GROUP_CHAT_ID).items)
    }

    @Test
    fun group_membership_changes_appear_in_the_timeline() = runTest {
        val client = demo()
        val chats = client.recordChats()
        val groupId = client.createGroupChat(" Adriatic Delivery ", listOf(lukas.userId, lukas.userId, me.userId))
        val timeline = client.record(groupId)
        assertEquals(
            listOf(
                TimelineItem.DateSeparator(dayStart(2026, 7, 12)),
                TimelineItem.MembershipChange(lukas.displayName, MembershipChangeKind.ADDED, base, lukas.userId, me.userId),
            ),
            timeline.items,
        )
        assertEquals(listOf(me, lukas), client.chatMembers(groupId))
        assertEquals(groupId, chats.ids.first())
        assertEquals("Adriatic Delivery", chats.chat(groupId).name)
        assertEquals(2, chats.chat(groupId).memberCount)

        client.addMembers(groupId, listOf(ana.userId, nikos.userId))
        val added = timeline.batches.last().single() as TimelineDiff.Append
        assertEquals(
            listOf(ana.displayName, nikos.displayName),
            added.values.map { (it as TimelineItem.MembershipChange).userName },
        )
        assertEquals(
            listOf(me.userId, me.userId),
            added.values.map { (it as TimelineItem.MembershipChange).actorUserId },
        )
        assertGimError<GimException.InvalidInput> { client.addMembers(groupId, listOf(ana.userId)) }
        assertGimError<GimException.NotFound> { client.addMembers(groupId, listOf("u-nobody")) }
        val skipped = timeline.batches.size
        assertGimError<GimException.InvalidInput> { client.addMembers(groupId, listOf("u-nobody", ana.userId)) }
        assertEquals(skipped, timeline.batches.size)

        client.removeMember(groupId, nikos.userId)
        assertEquals(
            TimelineDiff.PushBack(
                TimelineItem.MembershipChange(nikos.displayName, MembershipChangeKind.REMOVED, base, nikos.userId, me.userId),
            ),
            timeline.batches.last().single(),
        )
        assertGimError<GimException.InvalidInput> { client.removeMember(groupId, me.userId) }
        assertEquals(listOf(me, lukas, ana), client.chatMembers(groupId))
        assertEquals(3, chats.chat(groupId).memberCount)

        client.setGroupInfo(groupId, "Adriatic Crew", avatarFilePath = null)
        assertEquals("Adriatic Crew", chats.chat(groupId).name)
        val avatar = tmp.newFile("crew.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        client.setGroupInfo(groupId, name = null, avatarFilePath = avatar.path)
        val ref = chats.chat(groupId).avatar!!
        assertEquals("Adriatic Crew", chats.chat(groupId).name)
        assertEquals("image/png", ref.mime)
        assertEquals(3L, ref.size)
        assertGimError<GimException.InvalidInput> { client.setGroupInfo(DIRECT_CHAT_ID, "x", null) }
        assertGimError<GimException.InvalidInput> { client.setGroupInfo(groupId, null, null) }
        assertGimError<GimException.InvalidInput> { client.setGroupInfo(groupId, " ", null) }
        assertEquals("Adriatic Crew", chats.chat(groupId).name)

        // leaveChat only submits the leave: the chat is Leaving until another member commits it, sends fail meanwhile.
        assertEquals(ChatState.ACTIVE, client.chatState(groupId))
        val beforeLeave = timeline.batches.size
        client.leaveChat(groupId)
        assertEquals(beforeLeave, timeline.batches.size)
        assertEquals(ChatState.LEAVING, client.chatState(groupId))
        assertEquals(ChatState.LEAVING, chats.chat(groupId).state)
        assertEquals(ChatState.LEAVING, client.timeline(groupId).chatState())
        assertNotActive(groupId, ChatState.LEAVING) { client.sendText(groupId, "Still here?", null) }
        assertNotActive(groupId, ChatState.LEAVING) { client.setGroupInfo(groupId, "Renamed", null) }
        // Leaving again re-sends the leave.
        client.leaveChat(groupId)

        client.commitLeave(groupId)
        assertEquals(
            TimelineDiff.PushBack(TimelineItem.MembershipChange(me.displayName, MembershipChangeKind.LEFT, base, me.userId, me.userId)),
            timeline.batches.last().single(),
        )
        // Like gim-core, the left chat stays in the list.
        assertTrue(groupId in chats.ids)
        assertEquals(ChatState.LEFT, chats.chat(groupId).state)
        assertEquals(listOf(lukas, ana), client.chatMembers(groupId))
        assertNotActive(groupId, ChatState.LEFT) { client.sendText(groupId, "Still here?", null) }
        assertNotActive(groupId, ChatState.LEFT) { client.addMembers(groupId, listOf(nikos.userId)) }
        assertNotActive(groupId, ChatState.LEFT) { client.leaveChat(groupId) }
    }

    @Test
    fun removal_by_another_member_makes_the_chat_left() = runTest {
        val client = demo()
        val chats = client.recordChats()
        val timeline = client.record(GROUP_CHAT_ID)
        client.simulateRemovedBy(GROUP_CHAT_ID, by = ana.userId)
        assertEquals(
            TimelineItem.MembershipChange(me.displayName, MembershipChangeKind.REMOVED, base, me.userId, ana.userId),
            timeline.items.last(),
        )
        assertEquals(ChatState.LEFT, chats.chat(GROUP_CHAT_ID).state)
        assertEquals(ChatState.LEFT, client.chatState(GROUP_CHAT_ID))
        assertNotActive(GROUP_CHAT_ID, ChatState.LEFT) { client.sendText(GROUP_CHAT_ID, "hello?", null) }
    }

    private suspend fun assertNotActive(chatId: String, state: ChatState, block: suspend () -> Unit) {
        val e = assertGimError<GimException.ChatNotActive> { block() }
        assertEquals(chatId, e.chatId)
        assertEquals(state, e.state)
    }

    @Test
    fun leave_is_committed_after_the_outbox_delay_in_automatic_mode() = runTest {
        val client = demo(manualOutbox = false)
        val timeline = client.record(GROUP_CHAT_ID)
        client.leaveChat(GROUP_CHAT_ID)
        assertFalse(timeline.items.any { it is TimelineItem.MembershipChange && it.change == MembershipChangeKind.LEFT })
        advanceTimeBy(201)
        assertEquals(
            TimelineItem.MembershipChange(me.displayName, MembershipChangeKind.LEFT, base + 200, me.userId, me.userId),
            timeline.items.last(),
        )
    }

    @Test
    fun invitations_of_unknown_users_fail_with_not_found_and_create_nothing() = runTest {
        val client = demo()
        val chats = client.recordChats()
        val before = chats.ids
        assertGimError<GimException.NotFound> { client.createDirectChat("u-nobody") }
        assertGimError<GimException.NotFound> { client.createGroupChat("Ghosts", listOf("u-nobody", "u-nobody-2")) }
        assertEquals(before, chats.ids)

        // Unknown invitees next to known ones are skipped.
        val group = client.createGroupChat("Mixed", listOf("u-nobody", ana.userId))
        assertEquals(listOf(me, ana), client.chatMembers(group))
    }

    @Test
    fun local_time_zone_moves_the_date_separators() = runTest {
        val client = demo()
        val timeline = client.record(DIRECT_CHAT_ID)
        val athens = timeline.items.filterIsInstance<TimelineItem.DateSeparator>().map { it.dayStartMs }
        assertEquals(listOf(dayStart(2026, 7, 10), dayStart(2026, 7, 11), dayStart(2026, 7, 12)), athens)

        // UTC-10: every Athens day start (21:00Z the day before) moves to that UTC day's 10:00Z.
        client.setLocalTimeZone { -10 * 3600 }
        val shifted = timeline.items.filterIsInstance<TimelineItem.DateSeparator>().map { it.dayStartMs }
        assertTrue(shifted.isNotEmpty())
        assertTrue(shifted.all { Math.floorMod(it, 86_400_000L) == 10 * 3_600_000L })

        client.setLocalTimeZone(null)
        assertEquals(athens, timeline.items.filterIsInstance<TimelineItem.DateSeparator>().map { it.dayStartMs })
    }

    @Test
    fun chat_members_lists_each_user_once_including_the_local_user() = runTest {
        val client = demo()
        assertEquals(listOf(me, lukas), client.chatMembers(DIRECT_CHAT_ID))
        assertEquals(listOf(me, ana, lukas, nikos), client.chatMembers(GROUP_CHAT_ID))
        val anaChat = client.createDirectChat(ana.userId)
        assertEquals(listOf(me, ana), client.chatMembers(anaChat))
        val group = client.createGroupChat("Ionian", listOf(me.userId, nikos.userId, nikos.userId))
        assertEquals(listOf(me, nikos), client.chatMembers(group))
        assertGimError<GimException.NotFound> { client.chatMembers("chat-missing") }
    }

    @Test
    fun chat_list_is_ordered_by_update_and_tracks_unread() = runTest {
        val client = demo()
        val chats = client.recordChats()
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), chats.ids)
        assertEquals(3, chats.chat(GROUP_CHAT_ID).unreadCount)
        assertEquals(2, chats.chat(DIRECT_CHAT_ID).memberCount)
        assertEquals(lukas.displayName, chats.chat(DIRECT_CHAT_ID).name)

        advanceTimeBy(1_000)
        val incoming = client.simulateIncoming(DIRECT_CHAT_ID, "Moored in Split", from = lukas.userId)
        assertEquals(listOf(ChatListDiff.Remove(1), ChatListDiff.Insert(0, chats.items[0])), chats.batches.last())
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), chats.ids)
        val direct = chats.chat(DIRECT_CHAT_ID)
        assertEquals(1, direct.unreadCount)
        assertEquals(base + 1_000, direct.updatedAtMs)
        assertEquals(MessagePreview(lukas.displayName, "Moored in Split", base + 1_000, isOwn = false), direct.lastMessage)

        client.markRead(DIRECT_CHAT_ID, incoming)
        assertEquals(0, (chats.batches.last().single() as ChatListDiff.Set).value.unreadCount)
        assertGimError<GimException.NotFound> { client.markRead(DIRECT_CHAT_ID, "missing") }

        advanceTimeBy(1_000)
        val photo = tmp.newFile("sunset.jpg").apply { writeBytes(ByteArray(16)) }
        client.sendMedia(GROUP_CHAT_ID, photo.path, "image/jpeg", "Sunset over Kea", replyTo = null)
        assertEquals(GROUP_CHAT_ID, chats.ids.first())
        assertEquals(MessagePreview(me.displayName, "Photo", base + 2_000, isOwn = true), chats.items[0].lastMessage)

        val pdf = tmp.newFile("charter.pdf").apply { writeText("charter") }
        client.sendMedia(GROUP_CHAT_ID, pdf.path, "application/pdf", caption = null, replyTo = null)
        assertEquals("File: charter.pdf", chats.items[0].lastMessage?.text)
        assertGimError<GimException.InvalidInput> {
            client.sendMedia(GROUP_CHAT_ID, File(tmp.root, "missing.jpg").path, "image/jpeg", null, null)
        }
    }

    @Test
    fun create_direct_chat_returns_the_existing_chat_and_find_user_searches_the_directory() = runTest {
        val client = demo()
        assertEquals(DIRECT_CHAT_ID, client.createDirectChat(lukas.userId))
        val anaChat = client.createDirectChat(ana.userId)
        assertNotEquals(DIRECT_CHAT_ID, anaChat)
        assertEquals(anaChat, client.createDirectChat(ana.userId))
        assertGimError<GimException.InvalidInput> { client.createDirectChat(me.userId) }
        assertGimError<GimException.NotFound> { client.createDirectChat("u-nobody") }

        assertEquals(ana, client.findUser(" @Ana "))
        assertNull(client.findUser("nobody"))
    }

    @Test
    fun fetch_media_and_thumbnail_write_files_to_the_destination() = runTest {
        val client = demo()
        val messages = client.record(DIRECT_CHAT_ID).messages
        val image = (messages.first { it.content is MessageContent.Image }.content as MessageContent.Image).media
        val file = (messages.first { it.content is MessageContent.File }.content as MessageContent.File).media

        val imagePath = client.fetchMedia(image.chatId, image.messageId, File(tmp.root, "media/boat.jpg").path)
        val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertArrayEquals(pngSignature, File(imagePath).readBytes().copyOf(4))
        val thumbPath = client.fetchThumbnail(image.chatId, image.messageId, File(tmp.root, "thumbs/boat.png").path)
        assertTrue(File(thumbPath!!).isFile)

        assertNull(client.fetchThumbnail(file.chatId, file.messageId, File(tmp.root, "thumbs/survey.png").path))
        assertTrue(File(client.fetchMedia(file.chatId, file.messageId, File(tmp.root, "survey.pdf").path)).isFile)
        assertGimError<GimException.NotFound> { client.fetchMedia(DIRECT_CHAT_ID, "missing", tmp.root.path) }

        val source = tmp.newFile("notes.txt").apply { writeText("Bring the spare impeller") }
        val sent = client.sendMedia(DIRECT_CHAT_ID, source.path, "text/plain", caption = null, replyTo = null)
        val copy = client.fetchMedia(DIRECT_CHAT_ID, sent, File(tmp.root, "out/notes.txt").path)
        assertEquals("Bring the spare impeller", File(copy).readText())
    }

    @Test
    fun typing_reports_other_users_per_chat_until_the_handle_is_closed() = runTest {
        val client = demo()
        val updates = mutableListOf<List<String>>()
        val handle = client.typing(GROUP_CHAT_ID) { updates += it }
        client.simulateTyping(DIRECT_CHAT_ID, lukas.userId, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, ana.userId, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, ana.userId, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, nikos.userId, typing = true)
        client.simulateTyping(GROUP_CHAT_ID, ana.userId, typing = false)
        assertEquals(
            listOf(emptyList(), listOf(ana.userId), listOf(ana.userId, nikos.userId), listOf(nikos.userId)),
            updates,
        )

        val late = mutableListOf<List<String>>()
        client.typing(GROUP_CHAT_ID) { late += it }
        assertEquals(listOf(listOf(nikos.userId)), late)

        assertFalse(handle.isFinished())
        handle.close()
        assertTrue(handle.isFinished())
        client.simulateTyping(GROUP_CHAT_ID, nikos.userId, typing = false)
        assertEquals(4, updates.size)

        client.setTyping(GROUP_CHAT_ID, active = true)
        assertTrue(client.isTyping(GROUP_CHAT_ID))
    }

    @Test
    fun connection_state_follows_sync_and_reachability() = runTest {
        val client = demo()
        val states = mutableListOf<ConnectionState>()
        client.connectionState { states += it }
        client.setNetworkReachable(false)
        client.setNetworkReachable(true)
        client.startSync()
        // Unreachable neither pauses sync nor forces OFFLINE.
        client.setNetworkReachable(false)
        // A failed connection waits OFFLINE for its retry; becoming reachable reconnects at once.
        client.setConnection(ConnectionState.OFFLINE)
        client.setNetworkReachable(true)
        client.setNetworkReachable(true)
        client.stopSync()
        client.setNetworkReachable(false)
        client.setNetworkReachable(true)
        client.setConnection(ConnectionState.SYNCING)
        val online = listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING, ConnectionState.CONNECTED)
        assertEquals(
            listOf(ConnectionState.OFFLINE) + online + ConnectionState.OFFLINE + online +
                ConnectionState.OFFLINE + ConnectionState.SYNCING,
            states,
        )
        assertEquals(1, client.activeSubscriptions)
    }
}
