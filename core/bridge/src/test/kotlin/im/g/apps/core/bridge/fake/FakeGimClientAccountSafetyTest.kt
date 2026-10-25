package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind
import im.g.apps.core.bridge.PrivacySettings
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.applyDiffs
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.ana
import im.g.apps.core.bridge.fake.DemoData.lukas
import im.g.apps.core.bridge.fake.DemoData.me
import im.g.apps.core.bridge.flow.itemsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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

// Profile photo, privacy and chat settings, recovery code rotation, reports and account deletion.
class FakeGimClientAccountSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val keepUrl = "https://keep.example.eu"

    private inline fun <reified T : GimException> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: GimException) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got $e")
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    private fun TestScope.demo(keepServerUrl: String? = keepUrl) =
        FakeGimClient.withDemoData(backgroundScope, clock = { now }, outboxDelays = null, keepServerUrl = keepServerUrl)

    /** Subscribes to the chat list and keeps it up to date by applying every diff batch. */
    private suspend fun FakeGimClient.liveChatList(): Pair<MutableList<ChatSummary>, MutableList<List<ChatListDiff>>> {
        val rows = mutableListOf<ChatSummary>()
        val batches = mutableListOf<List<ChatListDiff>>()
        chatList { diffs ->
            batches += diffs
            rows.applyDiffs(diffs)
        }
        return rows to batches
    }

    private fun MutableList<ChatSummary>.row(chatId: String) = first { it.chatId == chatId }

    private suspend fun FakeGimClient.messages(chatId: String) =
        timeline(chatId).itemsFlow().first().filterIsInstance<TimelineItem.Message>().map { it.item }

    private fun image(): File = tmp.newFile("photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun ownProfileFollowsTheSessionAndTheAvatar() = runTest {
        val signedOut = FakeGimClient(backgroundScope, outboxDelays = null)
        assertNull(signedOut.ownProfile())

        val client = demo()
        assertEquals(UserProfile(me.userId, me.handle, me.displayName, hasAvatar = false), client.ownProfile())

        client.setAvatar(image().path, "image/png")
        assertTrue(client.ownProfile()!!.hasAvatar)
        val dest = File(tmp.root, "avatars/me.png")
        assertEquals(dest.path, client.fetchUserAvatar(me.userId, dest.path))
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())

        client.removeAvatar()
        assertFalse(client.ownProfile()!!.hasAvatar)
        assertThrows<GimException.NotFound> { client.fetchUserAvatar(me.userId, dest.path) }
    }

    @Test
    fun setAvatarRejectsAMissingFile() = runTest {
        val client = demo()
        assertThrows<GimException.InvalidInput> { client.setAvatar(File(tmp.root, "nope.png").path, "image/png") }
        assertFalse(client.ownProfile()!!.hasAvatar)
    }

    @Test
    fun fetchUserAvatarWritesAPlaceholderPngForUsersWithAPhoto() = runTest {
        val client = demo()
        val elena = UserProfile("u-elena", "elena", "Elena Rossi", hasAvatar = true)
        client.addDirectoryUser(elena)

        val dest = File(tmp.root, "elena.png")
        client.fetchUserAvatar(elena.userId, dest.path)
        val png = dest.readBytes()
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))

        assertThrows<GimException.NotFound> { client.fetchUserAvatar(lukas.userId, dest.path) }
        assertThrows<GimException.NotFound> { client.fetchUserAvatar("u-unknown", dest.path) }
    }

    @Test
    fun fetchChatAvatarReturnsTheGroupAvatar() = runTest {
        val client = demo()
        val dest = File(tmp.root, "group.png")
        assertThrows<GimException.NotFound> { client.fetchChatAvatar(GROUP_CHAT_ID, dest.path) }

        client.setGroupInfo(GROUP_CHAT_ID, null, image().path)
        assertEquals(dest.path, client.fetchChatAvatar(GROUP_CHAT_ID, dest.path))
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
        assertThrows<GimException.NotFound> { client.fetchChatAvatar("chat-unknown", dest.path) }
    }

    @Test
    fun directChatsCarryThePeer() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()
        assertEquals(lukas, rows.row(DIRECT_CHAT_ID).peer)
        assertNull(rows.row(GROUP_CHAT_ID).peer)
    }

    @Test
    fun muteSetsTheSummaryAndUnmuteClearsIt() = runTest {
        val client = demo()
        val (rows, batches) = client.liveChatList()

        client.setChatMuted(GROUP_CHAT_ID, now + 3_600_000)
        assertEquals(now + 3_600_000, rows.row(GROUP_CHAT_ID).mutedUntilMs)
        assertTrue(batches.last().single() is ChatListDiff.Set)

        client.setChatMuted(GROUP_CHAT_ID, client.mutedForever())
        assertEquals(Long.MAX_VALUE, rows.row(GROUP_CHAT_ID).mutedUntilMs)
        client.setChatMuted(GROUP_CHAT_ID, 0)
        assertEquals(0L, rows.row(GROUP_CHAT_ID).mutedUntilMs)

        assertThrows<GimException.NotFound> { client.setChatMuted("chat-unknown", 0) }
        assertThrows<GimException.InvalidInput> { client.setChatMuted(GROUP_CHAT_ID, -1) }
    }

    @Test
    fun mutedForeverIsTheLargestTimestamp() = runTest {
        assertEquals(Long.MAX_VALUE, demo().mutedForever())
    }

    @Test
    fun pinnedChatsComeFirstThenTheMostRecentlyUpdated() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), rows.map { it.chatId })

        client.setChatPinned(DIRECT_CHAT_ID, true)
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), rows.map { it.chatId })
        assertTrue(rows.row(DIRECT_CHAT_ID).pinned)

        // A newer message in an unpinned chat does not move it above a pinned one.
        client.simulateIncoming(GROUP_CHAT_ID, "Anyone?", from = ana.userId)
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), rows.map { it.chatId })

        client.setChatPinned(DIRECT_CHAT_ID, false)
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), rows.map { it.chatId })
        assertEquals(rows, client.liveChatList().first)
        assertThrows<GimException.NotFound> { client.setChatPinned("chat-unknown", true) }
    }

    @Test
    fun archiveOnlySetsTheFlagAndSurvivesNewMessages() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()

        client.setChatArchived(DIRECT_CHAT_ID, true)
        assertTrue(rows.row(DIRECT_CHAT_ID).archived)
        client.simulateIncoming(DIRECT_CHAT_ID, "Still there?", from = lukas.userId)
        assertTrue(rows.row(DIRECT_CHAT_ID).archived)

        client.setChatArchived(DIRECT_CHAT_ID, false)
        assertFalse(rows.row(DIRECT_CHAT_ID).archived)
        assertThrows<GimException.NotFound> { client.setChatArchived("chat-unknown", true) }
    }

    @Test
    fun privacyDefaultsAndSetters() = runTest {
        val client = demo()
        assertEquals(PrivacySettings(readReceipts = true, typingIndicators = true, blockedUserIds = emptyList()), client.privacy())

        client.setReadReceipts(false)
        client.setTypingIndicators(false)
        assertEquals(PrivacySettings(readReceipts = false, typingIndicators = false), client.privacy())

        assertThrows<GimException.NotRegistered> { FakeGimClient(backgroundScope).privacy() }
    }

    @Test
    fun readReceiptsOffStopsSendsAtDelivered() = runTest {
        val client = demo()
        client.setReadReceipts(false)
        val id = client.sendText(DIRECT_CHAT_ID, "hi", null)
        repeat(4) { client.advanceOutbox() }
        assertEquals(SendState.Delivered, client.messages(DIRECT_CHAT_ID).first { it.messageId == id }.sendState)
    }

    @Test
    fun typingIndicatorsOffSendsNothing() = runTest {
        val client = demo()
        client.setTypingIndicators(false)
        client.setTyping(DIRECT_CHAT_ID, true)
        assertFalse(client.isTyping(DIRECT_CHAT_ID))
    }

    @Test
    fun blockMarksTheDirectChatAndRefusesNewChats() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()

        client.blockUser(lukas.userId)
        assertEquals(listOf(lukas.userId), client.privacy().blockedUserIds)
        assertTrue(rows.row(DIRECT_CHAT_ID).blocked)
        assertFalse(rows.row(GROUP_CHAT_ID).blocked)

        assertThrows<GimException.InvalidInput> { client.createDirectChat(lukas.userId) }
        assertThrows<GimException.InvalidInput> { client.createGroupChat("Crew", listOf(lukas.userId, ana.userId)) }

        client.unblockUser(lukas.userId)
        assertEquals(emptyList<String>(), client.privacy().blockedUserIds)
        assertFalse(rows.row(DIRECT_CHAT_ID).blocked)
        assertEquals(DIRECT_CHAT_ID, client.createDirectChat(lukas.userId))
    }

    @Test
    fun addMembersRefusesBlockedUsers() = runTest {
        val client = demo()
        val elena = UserProfile("u-elena", "elena", "Elena Rossi")
        client.addDirectoryUser(elena)
        client.blockUser(elena.userId)
        assertThrows<GimException.InvalidInput> { client.addMembers(GROUP_CHAT_ID, listOf(elena.userId)) }
    }

    @Test
    fun messagesOfBlockedUsersAreDropped() = runTest {
        val client = demo()
        client.blockUser(lukas.userId)
        val before = client.messages(DIRECT_CHAT_ID).size
        client.simulateIncoming(DIRECT_CHAT_ID, "hello?", from = lukas.userId)
        assertEquals(before, client.messages(DIRECT_CHAT_ID).size)
    }

    @Test
    fun blockingYourselfIsInvalid() = runTest {
        val client = demo()
        assertThrows<GimException.InvalidInput> { client.blockUser(me.userId) }
        assertThrows<GimException.InvalidInput> { client.unblockUser(me.userId) }
    }

    @Test
    fun rotateRecoveryCodeReturnsANewValidCode() = runTest {
        val client = demo()
        val first = client.rotateRecoveryCode()
        val second = client.rotateRecoveryCode()
        assertNotEquals(first.recoveryCode, second.recoveryCode)
        assertEquals(emptyList<String>(), first.unsyncedHubIds)
        for (code in listOf(first.recoveryCode, second.recoveryCode)) {
            assertEquals(code.replace("-", ""), FakeRecoveryKitCodec.parse(code).recoveryCode)
        }

        client.rotationUnsyncedHubIds = listOf("hub-b")
        assertEquals(listOf("hub-b"), client.rotateRecoveryCode().unsyncedHubIds)
        assertThrows<GimException.NotRegistered> { FakeGimClient(backgroundScope).rotateRecoveryCode() }
    }

    @Test
    fun rotatedCodeRestoresTheAccount() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null)
        client.createIdentity("Alice")
        val userId = client.userId()!!
        client.addRecoverableUser(UserProfile(userId, "", "Alice"), FakeGimClient.fakeRecoveryCode(1))
        val rotated = client.rotateRecoveryCode().recoveryCode
        client.signOut()

        assertThrows<GimException.Crypto> { client.restoreFromRecovery(userId, FakeGimClient.fakeRecoveryCode(1), false) }
        client.restoreFromRecovery(userId, rotated, false)
        assertEquals(userId, client.userId())
    }

    @Test
    fun reportUserValidatesAndRecordsTheReport() = runTest {
        val client = demo()
        val messageId = client.messages(DIRECT_CHAT_ID).first { !it.isOwn }.messageId

        val reportId = client.reportUser(lukas.userId, ReportReason.HARASSMENT, "rude", listOf(messageId))
        assertEquals(
            listOf(FakeGimClient.Report(reportId, lukas.userId, ReportReason.HARASSMENT, "rude", listOf(messageId))),
            client.reports,
        )

        assertThrows<GimException.InvalidInput> { client.reportUser(me.userId, ReportReason.SPAM, null, emptyList()) }
        assertThrows<GimException.InvalidInput> {
            client.reportUser(lukas.userId, ReportReason.SPAM, null, List(51) { messageId })
        }
        assertThrows<GimException.InvalidInput> {
            client.reportUser(lukas.userId, ReportReason.OTHER, "x".repeat(1001), emptyList())
        }
        assertThrows<GimException.NotFound> {
            client.reportUser(lukas.userId, ReportReason.SCAM, null, listOf("m-unknown"))
        }
        assertEquals(1, client.reports.size)

        client.reportUser(lukas.userId, ReportReason.INAPPROPRIATE, "x".repeat(1000), List(50) { messageId })
        assertEquals(2, client.reports.size)
    }

    @Test
    fun reportUserNeedsAKeepServer() = runTest {
        val client = demo(keepServerUrl = null)
        assertThrows<GimException.InvalidInput> { client.reportUser(lukas.userId, ReportReason.SPAM, null, emptyList()) }
        assertTrue(client.reports.isEmpty())
    }

    @Test
    fun deleteAccountWipesLikeSignOutAndReturnsUnreachableHubs() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()
        client.unreachableHubsOnDelete = listOf("hub-b")

        assertEquals(listOf("hub-b"), client.deleteAccount())
        assertNull(client.session())
        assertTrue(rows.isEmpty())
        assertThrows<GimException.NotRegistered> { client.deleteAccount() }
    }

    @Test
    fun failedDeleteAccountKeepsEverythingAndCanBeRepeated() = runTest {
        val client = demo()
        client.failNext("deleteAccount", GimException.Network("offline"))

        assertThrows<GimException.Network> { client.deleteAccount() }
        assertEquals(DemoData.session, client.session())
        assertEquals(emptyList<String>(), client.deleteAccount())
        assertNull(client.session())
    }

    @Test
    fun failNextFailsOneCallOfTheNamedMethod() = runTest {
        val client = demo()
        client.failNext("setChatMuted", GimException.Storage("disk full"))
        assertThrows<GimException.Storage> { client.setChatMuted(GROUP_CHAT_ID, 1) }
        client.setChatMuted(GROUP_CHAT_ID, 1)
    }

    @Test
    fun ownPreviewsCarryTheSendStateAsItAdvances() = runTest {
        val client = demo()
        val (rows, _) = client.liveChatList()
        assertNull(rows.row(GROUP_CHAT_ID).lastMessage!!.sendState)

        client.sendText(GROUP_CHAT_ID, "On my way", null)
        assertEquals(SendState.Pending, rows.row(GROUP_CHAT_ID).lastMessage!!.sendState)
        client.advanceOutbox()
        assertEquals(SendState.Sent, rows.row(GROUP_CHAT_ID).lastMessage!!.sendState)
        client.advanceOutbox()
        assertEquals(SendState.Delivered, rows.row(GROUP_CHAT_ID).lastMessage!!.sendState)

        client.simulateIncoming(GROUP_CHAT_ID, "See you", from = ana.userId)
        assertNull(rows.row(GROUP_CHAT_ID).lastMessage!!.sendState)
    }

    @Test
    fun pushForAMutedChatIsNotDisplayed() = runTest {
        val client = demo()
        client.pushContent = {
            NotificationContent(
                GROUP_CHAT_ID, "Aegean Crew", ana.userId, ana.displayName, "hi", "m-1", "m-1",
                NotificationKind.MESSAGE, shouldDisplay = true, timestamp = now,
            )
        }
        assertTrue(client.processPush("{}").shouldDisplay)

        client.setChatMuted(GROUP_CHAT_ID, now + 1)
        assertFalse(client.processPush("{}").shouldDisplay)

        client.setChatMuted(GROUP_CHAT_ID, now)
        assertTrue(client.processPush("{}").shouldDisplay)
    }
}
