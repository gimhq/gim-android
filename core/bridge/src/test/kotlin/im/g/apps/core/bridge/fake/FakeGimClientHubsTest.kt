package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeGimClientHubsTest {
    private val homeUrl = "http://10.0.2.2:8080"
    private val secondUrl = "http://10.0.2.2:8081"
    private val thirdUrl = "https://hub-c.example.eu"

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null, homeHubUrl = homeUrl).also { it.createIdentity("Alice") }

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
    fun singleHubIsTheHomeHub() = runTest {
        val client = signedIn()

        assertEquals(
            listOf(
                HubInfo(
                    FakeGimClient.fakeHubId(homeUrl), homeUrl, HubState.VERIFIED, 0,
                    isPrimary = true, isHome = true, signedIn = true,
                ),
            ),
            client.hubs(),
        )
    }

    @Test
    fun hubsNeedASession() = runTest {
        val client = FakeGimClient(backgroundScope, outboxDelays = null)
        assertGimError<GimException.NotRegistered> { client.hubs() }
        assertGimError<GimException.NotRegistered> { client.addHub(secondUrl) }
    }

    @Test
    fun addHubListsItLastAndIsIdempotent() = runTest {
        val client = signedIn()

        val b = client.addHub("$secondUrl/")
        val c = client.addHub(thirdUrl)

        assertEquals(FakeGimClient.fakeHubId(secondUrl), b.hubId)
        assertEquals(secondUrl, b.url)
        assertEquals(1, b.priority)
        assertFalse(b.isPrimary || b.isHome)
        assertTrue(b.signedIn)
        assertEquals(2, c.priority)
        assertEquals(b, client.addHub(secondUrl))
        assertEquals(listOf(homeUrl, secondUrl, thirdUrl), client.hubs().map { it.url })
    }

    @Test
    fun addHubRejectsBadAndUnverifiedUrls() = runTest {
        val client = signedIn()
        client.rejectHubUrl(secondUrl)

        val invalid = assertGimError<GimException.InvalidInput> { client.addHub("ftp://hub") }
        assertTrue(invalid.reason.startsWith("url:"))
        assertGimError<GimException.HubNotVerified> { client.addHub(secondUrl) }
        assertEquals(1, client.hubs().size)
    }

    @Test
    fun homeHubCannotBeRemoved() = runTest {
        val client = signedIn()
        val home = FakeGimClient.fakeHubId(homeUrl)

        assertGimError<GimException.HomeHub> { client.removeHub(home, confirmOwnedGroups = true) }
        assertGimError<GimException.InvalidInput> { client.removeHub("hub-unknown", confirmOwnedGroups = true) }

        // Not even once another hub is primary.
        client.setPrimaryHub(client.addHub(secondUrl).hubId)
        assertGimError<GimException.HomeHub> { client.removeHub(home, confirmOwnedGroups = false) }
    }

    @Test
    fun lastListedHubCannotBeRemoved() = runTest {
        val client = signedIn()
        val home = FakeGimClient.fakeHubId(homeUrl)
        val b = client.addHub(secondUrl).hubId

        client.simulateHubRemovedByOtherDevice(home)

        assertGimError<GimException.LastHub> { client.removeHub(b, confirmOwnedGroups = true) }
        assertGimError<GimException.InvalidInput> { client.removeHub(home, confirmOwnedGroups = true) }
        assertEquals(listOf(b, home), client.hubs().map { it.hubId })
        assertTrue(client.hubs().first().isPrimary)
    }

    @Test
    fun removeHubAsksBeforeDroppingOwnedChats() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        val b = client.addHub(secondUrl).hubId
        client.simulateChatOwnedBy(DIRECT_CHAT_ID, b)
        client.simulateChatOwnedBy(GROUP_CHAT_ID, b)

        val owns = assertGimError<GimException.HubOwnsGroups> { client.removeHub(b, confirmOwnedGroups = false) }
        assertEquals(b, owns.hubId)
        assertEquals(2, owns.groupCount)

        client.removeHub(b, confirmOwnedGroups = true)
        val removed = client.hubs().last()
        assertEquals(HubState.REMOVED, removed.state)
        assertFalse(removed.signedIn || removed.isPrimary)
    }

    @Test
    fun removeHubWaitsForTheOutbox() = runTest {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        val b = client.addHub(secondUrl).hubId
        client.sendText(DIRECT_CHAT_ID, "pending", null)

        val e = assertGimError<GimException.HubNotDrained> { client.removeHub(b, confirmOwnedGroups = false) }
        assertEquals(b, e.hubId)

        repeat(3) { client.advanceOutbox() }
        client.removeHub(b, confirmOwnedGroups = false)
        assertEquals(HubState.REMOVED, client.hubs().single { it.hubId == b }.state)
    }

    @Test
    fun setPrimaryHubReordersTheOthers() = runTest {
        val client = signedIn()
        val home = FakeGimClient.fakeHubId(homeUrl)
        val b = client.addHub(secondUrl).hubId
        val c = client.addHub(thirdUrl).hubId

        client.setPrimaryHub(c)

        assertEquals(listOf(c to 0, home to 1, b to 2), client.hubs().map { it.hubId to it.priority })
        assertEquals(listOf(true, false, false), client.hubs().map { it.isPrimary })
        assertTrue(client.hubs().single { it.hubId == home }.isHome)

        client.setPrimaryHub(c) // already primary: nothing changes
        assertGimError<GimException.InvalidInput> { client.setPrimaryHub("hub-unknown") }
    }

    @Test
    fun setPrimaryHubNeedsASessionOnEveryReprioritizedHub() = runTest {
        val client = signedIn()
        val shared = "hub-shared"
        client.simulateHubSharedByOtherDevice(shared, secondUrl)
        assertEquals(HubState.UNVERIFIED, client.hubs().last().state)

        assertGimError<GimException.InvalidInput> { client.setPrimaryHub(shared) }
        assertEquals(FakeGimClient.fakeHubId(homeUrl), client.hubs().first().hubId)
    }

    @Test
    fun hubListDeliversCurrentThenChangesUntilCancelled() = runTest {
        val client = signedIn()
        val updates = mutableListOf<List<HubInfo>>()

        val handle = client.hubList { updates += it }
        client.addHub(secondUrl)
        handle.cancel()
        client.addHub(thirdUrl)

        assertEquals(listOf(1, 2), updates.map { it.size })
        assertTrue(handle.isFinished())
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun signOutClearsTheHubs() = runTest {
        val client = signedIn()
        client.addHub(secondUrl)
        val updates = mutableListOf<List<HubInfo>>()
        client.hubList { updates += it }

        client.signOut()

        assertEquals(emptyList<HubInfo>(), updates.last())
        assertGimError<GimException.NotRegistered> { client.hubs() }
    }
}
