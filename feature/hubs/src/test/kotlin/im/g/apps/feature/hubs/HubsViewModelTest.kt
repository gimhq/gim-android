package im.g.apps.feature.hubs

import androidx.lifecycle.viewModelScope
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class Loaded(val vm: HubsViewModel, val client: TestClient, val fake: FakeGimClient)

    private suspend fun TestScope.loaded(withHubB: Boolean = false): Loaded {
        val fake = signedInClient(backgroundScope)
        if (withHubB) fake.addHub(HUB_B)
        val client = TestClient(fake)
        val vm = HubsViewModel(client)
        advanceUntilIdle()
        return Loaded(vm, client, fake)
    }

    private fun Loaded.addHub(url: String) {
        vm.openAddHub()
        vm.addHubUrlChanged(url)
        vm.submitAddHub()
    }

    @Test
    fun lists_the_home_hub_as_primary_verified_and_signed_in() = runTest {
        val vm = loaded().vm

        val state = vm.state.value
        assertFalse(state.loading)
        val home = state.hubs.single()
        assertEquals(HUB_A_ID, home.hubId)
        assertTrue(home.isHome && home.isPrimary && home.signedIn)
        assertEquals(HubState.VERIFIED, home.state)
        assertTrue(state.removed.isEmpty())
        assertFalse(state.canMakePrimary(home))
        assertTrue(state.canRemove(home))
    }

    @Test
    fun added_hub_closes_the_dialog_and_is_listed_last() = runTest {
        val t = loaded()

        t.addHub(" $HUB_B ")
        assertTrue(t.vm.state.value.addHub!!.submitting)
        advanceUntilIdle()

        val state = t.vm.state.value
        assertNull(state.addHub)
        assertEquals(listOf(HUB_A_ID, HUB_B_ID), state.hubs.map { it.hubId })
        assertTrue(state.hubs[1].signedIn)
        assertTrue(state.canMakePrimary(state.hubs[1]))
    }

    @Test
    fun add_errors_are_shown_inline_and_cleared_on_edit() = runTest {
        val t = loaded()

        t.addHub("ftp://nope")
        advanceUntilIdle()
        assertEquals(AddHubState("ftp://nope", submitting = false, error = HubsError.InvalidUrl), t.vm.state.value.addHub)
        assertNull(t.vm.state.value.error)

        t.vm.addHubUrlChanged(HUB_B)
        assertNull(t.vm.state.value.addHub!!.error)
    }

    @Test
    fun add_maps_network_not_verified_and_retry_later() = runTest {
        val t = loaded()
        t.fake.rejectHubUrl(HUB_B)
        t.addHub(HUB_B)
        advanceUntilIdle()
        assertEquals(HubsError.NotVerified, t.vm.state.value.addHub!!.error)

        t.client.addError = GimException.Network("connection refused")
        t.vm.submitAddHub()
        advanceUntilIdle()
        assertEquals(HubsError.Unreachable, t.vm.state.value.addHub!!.error)

        t.client.addError = GimException.RetryLater(429, 12, "rate_limited")
        t.vm.submitAddHub()
        advanceUntilIdle()
        assertEquals(HubsError.RetryLater(12), t.vm.state.value.addHub!!.error)
        assertEquals(1, t.vm.state.value.hubs.size)
    }

    @Test
    fun blank_url_is_not_submitted_and_dismiss_closes_the_dialog() = runTest {
        val t = loaded()
        t.addHub("  ")
        assertFalse(t.vm.state.value.addHub!!.submitting)

        t.vm.dismissAddHub()
        assertNull(t.vm.state.value.addHub)
    }

    @Test
    fun make_primary_reorders_the_list() = runTest {
        val t = loaded(withHubB = true)
        val b = t.vm.state.value.hubs[1]

        t.vm.makePrimary(b)
        assertTrue(t.vm.state.value.working)
        advanceUntilIdle()

        val state = t.vm.state.value
        assertFalse(state.working)
        assertEquals(listOf(HUB_B_ID, HUB_A_ID), state.hubs.map { it.hubId })
        assertTrue(state.hubs[0].isPrimary)
        assertTrue(state.hubs[1].isHome && !state.hubs[1].isPrimary)
    }

    @Test
    fun make_primary_is_refused_for_primary_unverified_and_removed_hubs() = runTest {
        val t = loaded()
        t.fake.simulateHubSharedByOtherDevice("hub-shared", url = null)
        advanceUntilIdle()
        val (home, shared) = t.vm.state.value.hubs

        assertFalse(t.vm.state.value.canMakePrimary(home))
        assertFalse(t.vm.state.value.canMakePrimary(shared))
        t.vm.makePrimary(shared)
        assertFalse(t.vm.state.value.working)

        t.fake.simulateHubRemovedByOtherDevice("hub-shared")
        advanceUntilIdle()
        val removed = t.vm.state.value.removed.single()
        assertFalse(t.vm.state.value.canMakePrimary(removed))
        assertFalse(t.vm.state.value.canRemove(removed))
    }

    @Test
    fun make_primary_invalid_input_means_a_hub_without_session() = runTest {
        val t = loaded(withHubB = true)
        t.client.primaryError = GimException.InvalidInput("hub is not reachable from this device")

        t.vm.makePrimary(t.vm.state.value.hubs[1])
        advanceUntilIdle()

        assertEquals(HubsError.CannotMakePrimary, t.vm.state.value.error)
        t.vm.errorShown()
        assertNull(t.vm.state.value.error)
    }

    @Test
    fun removing_the_home_hub_explains_it() = runTest {
        val t = loaded(withHubB = true)

        t.vm.remove(t.vm.state.value.hubs[0])
        advanceUntilIdle()

        assertEquals(HubsError.HomeHub, t.vm.state.value.error)
        assertEquals(2, t.vm.state.value.hubs.size)
    }

    @Test
    fun removing_the_last_hub_asks_for_another_one() = runTest {
        val t = loaded()
        t.client.removeError = GimException.LastHub(HUB_A_ID)

        t.vm.remove(t.vm.state.value.hubs[0])
        advanceUntilIdle()

        assertEquals(HubsError.LastHub, t.vm.state.value.error)
    }

    @Test
    fun removing_a_hub_owning_groups_asks_then_removes_with_confirmation() = runTest {
        val t = loaded(withHubB = true)
        val chatId = t.fake.createGroupChat("Sailing club", emptyList())
        t.fake.simulateChatOwnedBy(chatId, HUB_B_ID)

        t.vm.remove(t.vm.state.value.hubs[1])
        advanceUntilIdle()
        assertEquals(HubsError.OwnsGroups(HUB_B_ID, 1), t.vm.state.value.error)
        assertEquals(2, t.vm.state.value.hubs.size)

        t.vm.confirmRemoveOwnedGroups()
        advanceUntilIdle()

        val state = t.vm.state.value
        assertNull(state.error)
        assertEquals(listOf(HUB_B_ID to false, HUB_B_ID to true), t.client.removeCalls)
        assertEquals(listOf(HUB_A_ID), state.hubs.map { it.hubId })
        assertEquals(HubState.REMOVED, state.removed.single().state)
        assertEquals(ChatState.ACTIVE, t.fake.chatState(chatId))
    }

    @Test
    fun dismissed_owned_groups_warning_keeps_the_hub() = runTest {
        val t = loaded(withHubB = true)
        t.client.removeError = GimException.HubOwnsGroups(HUB_B_ID, 3)

        t.vm.remove(t.vm.state.value.hubs[1])
        advanceUntilIdle()
        t.vm.errorShown()
        t.vm.confirmRemoveOwnedGroups()
        advanceUntilIdle()

        assertEquals(1, t.client.removeCalls.size)
        assertEquals(2, t.vm.state.value.hubs.size)
    }

    @Test
    fun remove_maps_not_drained_not_listed_and_network() = runTest {
        val t = loaded(withHubB = true)
        val b = t.vm.state.value.hubs[1]

        t.client.removeError = GimException.HubNotDrained(HUB_B_ID, "1 messages and 0 commits not acknowledged yet")
        t.vm.remove(b)
        advanceUntilIdle()
        assertEquals(HubsError.NotDrained, t.vm.state.value.error)

        t.client.removeError = GimException.InvalidInput("hub is not listed")
        t.vm.remove(b)
        advanceUntilIdle()
        assertEquals(HubsError.NotListed, t.vm.state.value.error)

        t.client.removeError = GimException.Network("timeout")
        t.vm.remove(b)
        advanceUntilIdle()
        assertEquals(HubsError.Unreachable, t.vm.state.value.error)
        assertFalse(t.vm.state.value.working)
    }

    @Test
    fun every_gim_exception_is_mapped() {
        val invalid = HubsError.InvalidUrl
        val cases = mapOf(
            GimException.InvalidInput("x") to invalid,
            GimException.Network("x") to HubsError.Unreachable,
            GimException.HubNotVerified("x") to HubsError.NotVerified,
            GimException.RetryLater(503, 7, "x") to HubsError.RetryLater(7),
            GimException.HubOwnsGroups("h", 2) to HubsError.OwnsGroups("h", 2),
            GimException.HomeHub("h") to HubsError.HomeHub,
            GimException.LastHub("h") to HubsError.LastHub,
            GimException.HubNotDrained("h", "x") to HubsError.NotDrained,
            GimException.NotRegistered() to HubsError.NotRegistered,
            GimException.Server(500, "x") to HubsError.Server,
            GimException.NotFound("x") to HubsError.Unknown,
            GimException.Crypto("x") to HubsError.Unknown,
            GimException.Storage("x") to HubsError.Unknown,
            GimException.Cancelled() to HubsError.Unknown,
            GimException.NotImplemented() to HubsError.Unknown,
            GimException.ChatNotActive("c", ChatState.LEFT) to HubsError.Unknown,
            GimException.Internal("x") to HubsError.Unknown,
            GimException.UserSuspended("h") to HubsError.Suspended(null),
        )
        cases.forEach { (e, expected) -> assertEquals(e.toString(), expected, e.toHubsError(invalid)) }
        assertEquals(HubsError.Suspended(HUB_B), GimException.UserSuspended(HUB_B_ID).toHubsError(invalid, HUB_B))
    }

    @Test
    fun suspension_names_the_hub_when_there_are_several_and_keeps_the_list() = runTest {
        val t = loaded(withHubB = true)
        val b = t.vm.state.value.hubs[1]

        t.client.removeError = GimException.UserSuspended(HUB_B_ID)
        t.vm.remove(b)
        advanceUntilIdle()

        assertEquals(HubsError.Suspended(HUB_B), t.vm.state.value.error)
        assertEquals(2, t.vm.state.value.hubs.size)
        assertEquals(1, t.client.removeCalls.size)
        assertFalse(t.vm.state.value.working)
        assertTrue(t.fake.session() != null)
    }

    @Test
    fun suspension_with_one_hub_or_an_unknown_hub_id_uses_the_generic_text() = runTest {
        val t = loaded()
        t.fake.simulateSuspension(HUB_A_ID)

        t.addHub(HUB_B)
        advanceUntilIdle()
        assertEquals(HubsError.Suspended(null), t.vm.state.value.addHub?.error)
        assertFalse(t.vm.state.value.addHub!!.submitting)

        t.fake.simulateSuspension(null)
        t.vm.dismissAddHub()
        t.fake.addHub(HUB_B)
        advanceUntilIdle()
        t.client.removeError = GimException.UserSuspended("")
        t.vm.remove(t.vm.state.value.hubs[1])
        advanceUntilIdle()
        assertEquals(HubsError.Suspended(null), t.vm.state.value.error)
    }

    @Test
    fun subscription_ends_when_the_view_model_is_cleared() = runTest {
        val fake = signedInClient(backgroundScope)
        val vm = HubsViewModel(TestClient(fake))
        advanceUntilIdle()
        assertEquals(1, fake.activeSubscriptions)

        vm.viewModelScope.cancel()
        advanceUntilIdle()
        assertEquals(0, fake.activeSubscriptions)
    }

    @Test
    fun hub_host_shows_host_and_port() {
        assertEquals("10.0.2.2:8081", hubHost(HUB_B))
        assertEquals("hub.example.eu", hubHost("https://hub.example.eu/"))
        assertNull(hubHost(null))
    }
}
