package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeGimClientSuspensionTest {
    private val homeUrl = "http://10.0.2.2:8080"
    private val secondUrl = "http://10.0.2.2:8081"
    private val homeId = FakeGimClient.fakeHubId(homeUrl)

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null, homeHubUrl = homeUrl).also { it.createIdentity("Alice") }

    private suspend fun assertSuspended(hubId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: GimException.UserSuspended) {
            assertEquals(hubId, e.hubId)
            return
        }
        fail("expected UserSuspended")
    }

    @Test
    fun suspensionFailsHubCallsAndKeepsSessionAndChats() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        val demoHub = client.hubs().single().hubId
        val chats = client.chatListFlow().first()
        client.simulateSuspension(demoHub)

        assertSuspended(demoHub) { client.sendText(DIRECT_CHAT_ID, "hi", null) }
        assertSuspended(demoHub) { client.addMembers(GROUP_CHAT_ID, listOf("u-x")) }
        assertSuspended(demoHub) { client.chatMembers(GROUP_CHAT_ID) }
        assertSuspended(demoHub) { client.devices() }
        assertSuspended(demoHub) { client.addHub(secondUrl) }
        assertNotNull(client.session())
        assertEquals(chats, client.chatListFlow().first())
        assertTrue(client.timeline(DIRECT_CHAT_ID).itemsFlow().first().isNotEmpty())

        client.simulateSuspension(null)
        client.sendText(DIRECT_CHAT_ID, "hi", null)
    }

    @Test
    fun suspendedHubNameNamesTheHubOnlyWithSeveralHubs() = runTest {
        val client = signedIn()
        assertNull(client.suspendedHubName(GimException.UserSuspended(homeId)))

        val second = client.addHub(secondUrl)
        assertEquals(secondUrl, client.suspendedHubName(GimException.UserSuspended(second.hubId)))
        assertEquals(homeUrl, client.suspendedHubName(GimException.UserSuspended(homeId)))
        assertNull(client.suspendedHubName(GimException.UserSuspended("")))
        assertNull(client.suspendedHubName(GimException.UserSuspended("unknown")))
        assertNull(client.suspendedHubName(GimException.Network("offline")))
    }

    @Test
    fun suspendedHubNameFallsBackWhenHubsCannotBeRead() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null)
        assertNull(client.suspendedHubName(GimException.UserSuspended(homeId)))
    }
}
