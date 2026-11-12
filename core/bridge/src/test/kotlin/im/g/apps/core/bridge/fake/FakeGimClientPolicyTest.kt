package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeGimClientPolicyTest {
    private val homeUrl = "http://10.0.2.2:8080"
    private val secondUrl = "http://10.0.2.2:8081"
    private val homeId = FakeGimClient.fakeHubId(homeUrl)
    private val elena = UserProfile("u-elena", "Elena Rossi")
    private val jan = UserProfile("u-jan", "Jan de Vries")

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

    private suspend fun FakeGimClient.items(chatId: String) = timeline(chatId).itemsFlow().first()

    private suspend fun FakeGimClient.lastMessage(chatId: String) =
        items(chatId).filterIsInstance<TimelineItem.Message>().last().item

    @Test
    fun notAdmittedRefusesRegistrationAndKeepsNothing() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null, homeHubUrl = homeUrl)
        client.simulateNotAdmitted("")

        assertEquals("", assertThrows<GimException.UserNotAdmitted> { client.createIdentity("Alice") }.hubId)
        assertNull(client.session())

        client.simulateNotAdmitted(null)
        client.createIdentity("Alice")
        assertNotNull(client.session())
    }

    @Test
    fun notAdmittedFailsHubCallsAndKeepsSession() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        val hub = client.hubs().single().hubId
        client.simulateNotAdmitted(hub)

        assertEquals(hub, assertThrows<GimException.UserNotAdmitted> { client.devices() }.hubId)
        assertThrows<GimException.UserNotAdmitted> { client.sendText(DIRECT_CHAT_ID, "hi", null) }
        assertNotNull(client.session())
        assertTrue(client.items(DIRECT_CHAT_ID).isNotEmpty())
    }

    @Test
    fun addHubRefusedLeavesTheHubUnlisted() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null, homeHubUrl = homeUrl)
        client.createIdentity("Alice")
        client.refuseHubUrl(secondUrl)

        val e = assertThrows<GimException.UserNotAdmitted> { client.addHub(secondUrl) }
        assertEquals(FakeGimClient.fakeHubId(secondUrl), e.hubId)
        assertEquals(listOf(homeId), client.hubs().map { it.hubId })
    }

    @Test
    fun suspendedHubNameNamesANotAdmittedHubOnlyWithSeveralHubs() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null, homeHubUrl = homeUrl)
        client.createIdentity("Alice")
        assertNull(client.suspendedHubName(GimException.UserNotAdmitted(homeId)))

        client.addHub(secondUrl)
        assertEquals(homeUrl, client.suspendedHubName(GimException.UserNotAdmitted(homeId)))
        assertNull(client.suspendedHubName(GimException.UserNotAdmitted("")))
    }

    @Test
    fun notAdmittedByPeerFailsThatChatOnly() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        client.addDirectoryUser(elena)
        client.simulateNotAdmittedByPeer(GROUP_CHAT_ID, "hub-owner")

        client.sendText(GROUP_CHAT_ID, "refused", null)
        val failed = client.lastMessage(GROUP_CHAT_ID).sendState as SendState.Failed
        assertEquals(SendFailure.NOT_ADMITTED_BY_PEER, failed.cause)

        val e = assertThrows<GimException.NotAdmittedByPeer> { client.addMembers(GROUP_CHAT_ID, listOf(elena.userId)) }
        assertEquals("hub-owner", e.hubId)
        assertEquals(GROUP_CHAT_ID, e.chatId)
        assertThrows<GimException.NotAdmittedByPeer> { client.removeMember(GROUP_CHAT_ID, "u-lukas") }
        assertThrows<GimException.NotAdmittedByPeer> { client.leaveChat(GROUP_CHAT_ID) }
        assertEquals(ChatState.ACTIVE, client.chatState(GROUP_CHAT_ID))

        client.sendText(DIRECT_CHAT_ID, "fine", null)
        assertEquals(SendState.Pending, client.lastMessage(DIRECT_CHAT_ID).sendState)
    }

    @Test
    fun failNextSendCarriesTheCause() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        client.failNextSend("user_suspended", SendFailure.USER_SUSPENDED)
        client.sendText(DIRECT_CHAT_ID, "lost", null)
        client.advanceOutbox()

        assertEquals(
            SendState.Failed("user_suspended", SendFailure.USER_SUSPENDED),
            client.lastMessage(DIRECT_CHAT_ID).sendState,
        )
    }

    @Test
    fun contactNotAdmittedFailsADirectChat() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        client.addDirectoryUser(elena)
        client.refuseContacts(elena.userId)

        val e = assertThrows<GimException.ContactNotAdmitted> { client.createDirectChat(elena.userId) }
        assertEquals(listOf(elena.userId), e.userIds)
    }

    @Test
    fun contactNotAdmittedSkipsRefusedInviteesOfAGroup() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        client.addDirectoryUser(elena)
        client.addDirectoryUser(jan)
        client.refuseContacts(elena.userId)

        val chatId = client.createGroupChat("Crew", listOf(elena.userId, jan.userId))
        assertEquals(setOf(jan.userId), client.chatMembers(chatId).map { it.userId }.toSet() - client.userId()!!)
        val changes = client.items(chatId).filterIsInstance<TimelineItem.MembershipChange>()
        assertEquals(
            listOf(jan.userId to MembershipChangeKind.ADDED, elena.userId to MembershipChangeKind.REMOVED),
            changes.map { it.userId to it.change },
        )

        val e = assertThrows<GimException.ContactNotAdmitted> { client.createGroupChat("Solo", listOf(elena.userId)) }
        assertEquals(listOf(elena.userId), e.userIds)
    }

    @Test
    fun contactNotAdmittedSkipsRefusedUsersOfAddMembers() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        client.addDirectoryUser(elena)
        client.addDirectoryUser(jan)
        client.refuseContacts(elena.userId)

        client.addMembers(GROUP_CHAT_ID, listOf(elena.userId, jan.userId))
        val members = client.chatMembers(GROUP_CHAT_ID).map { it.userId }
        assertTrue(jan.userId in members)
        assertTrue(elena.userId !in members)

        val e = assertThrows<GimException.ContactNotAdmitted> { client.addMembers(GROUP_CHAT_ID, listOf(elena.userId)) }
        assertEquals(listOf(elena.userId), e.userIds)
    }
}
