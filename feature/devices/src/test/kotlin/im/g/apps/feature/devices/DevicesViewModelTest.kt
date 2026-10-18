package im.g.apps.feature.devices

import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.loaded(linked: Int = 1): Triple<DevicesViewModel, TestClient, List<String>> {
        val (fake, ids) = signedInClient(backgroundScope, linked)
        val client = TestClient(fake)
        val vm = DevicesViewModel(client)
        advanceUntilIdle()
        return Triple(vm, client, ids)
    }

    @Test
    fun suspension_shows_the_generic_text_keeps_the_list_and_does_not_sign_out() = runTest {
        val (vm, client, _) = loaded()
        client.devicesError = GimException.UserSuspended(client.hubs().single().hubId)

        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(DevicesError.Suspended(null), state.error)
        assertTrue(state.suspended)
        assertFalse(state.removed)
        assertEquals(2, state.devices.size)
        assertNotNull(client.session())

        client.devicesError = null
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.suspended)
    }

    @Test
    fun not_admitted_shows_the_policy_text_offers_no_retry_and_does_not_sign_out() = runTest {
        val (vm, client, _) = loaded()
        client.devicesError = GimException.UserNotAdmitted(client.hubs().single().hubId)

        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(DevicesError.NotAdmitted(null), state.error)
        assertTrue(state.suspended)
        assertFalse(state.removed)
        assertNotNull(client.session())
    }

    @Test
    fun suspension_on_another_hub_names_it() = runTest {
        val (vm, client, _) = loaded()
        val other = client.addHub("https://hub-b.example.eu")
        client.revokeError = GimException.UserSuspended(other.hubId)

        vm.requestRevoke(vm.state.value.devices[1])
        vm.confirmRevoke()
        advanceUntilIdle()

        assertEquals(DevicesError.Suspended("https://hub-b.example.eu"), vm.state.value.error)
        assertFalse(vm.state.value.revoking || vm.state.value.removed)
    }

    @Test
    fun loads_devices_with_this_device_first_and_the_user_id() = runTest {
        val (vm, client, ids) = loaded()

        val state = vm.state.value
        assertEquals(client.userId(), state.userId)
        assertFalse(state.loading)
        assertEquals(2, state.devices.size)
        assertTrue(state.devices[0].isCurrent)
        assertEquals(ids.single(), state.devices[1].deviceId)
        assertEquals(CREATED_AT_MS, state.devices[1].createdAtMs)
    }

    @Test
    fun revoked_devices_are_listed_after_active_ones() = runTest {
        val (vm, client, ids) = loaded(linked = 2)
        client.revokeDevice(ids[0])

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(false, false, true), vm.state.value.devices.map { it.revoked })
        assertEquals(ids[0], vm.state.value.devices.last().deviceId)
        assertEquals(2, client.devicesCalls)
    }

    @Test
    fun confirmed_revoke_revokes_the_device_and_reloads() = runTest {
        val (vm, _, ids) = loaded()
        val other = vm.state.value.devices[1]

        vm.requestRevoke(other)
        assertEquals(other, vm.state.value.pendingRevoke)
        vm.confirmRevoke()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.pendingRevoke)
        assertFalse(state.revoking)
        assertTrue(state.devices.single { it.deviceId == ids.single() }.revoked)
        assertNull(state.error)
    }

    @Test
    fun dismissed_revoke_keeps_the_device() = runTest {
        val (vm, _, _) = loaded()

        vm.requestRevoke(vm.state.value.devices[1])
        vm.dismissRevoke()
        vm.confirmRevoke()
        advanceUntilIdle()

        assertNull(vm.state.value.pendingRevoke)
        assertFalse(vm.state.value.devices[1].revoked)
    }

    @Test
    fun current_and_revoked_devices_cannot_be_revoked() = runTest {
        val (vm, client, ids) = loaded()
        client.revokeDevice(ids.single())
        vm.refresh()
        advanceUntilIdle()

        vm.requestRevoke(vm.state.value.devices[0])
        assertNull(vm.state.value.pendingRevoke)
        vm.requestRevoke(vm.state.value.devices[1])
        assertNull(vm.state.value.pendingRevoke)
    }

    @Test
    fun invalid_input_from_revoke_is_reported_as_current_device() = runTest {
        val (vm, client, _) = loaded()
        client.revokeError = GimException.InvalidInput("cannot revoke the current device")

        vm.requestRevoke(vm.state.value.devices[1])
        vm.confirmRevoke()
        advanceUntilIdle()

        assertEquals(DevicesError.CurrentDevice, vm.state.value.error)
        assertFalse(vm.state.value.revoking)
        assertFalse(vm.state.value.devices[1].revoked)
    }

    @Test
    fun retry_later_is_reported_with_its_delay_and_cleared_once_shown() = runTest {
        val (fake, _) = signedInClient(backgroundScope)
        val client = TestClient(fake).apply { devicesError = GimException.RetryLater(429, 30, "rate_limited") }

        val vm = DevicesViewModel(client)
        advanceUntilIdle()

        assertEquals(DevicesError.RetryLater(30), vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.devices.isEmpty())
        vm.errorShown()
        assertNull(vm.state.value.error)
    }

    @Test
    fun network_error_on_refresh_keeps_the_list() = runTest {
        val (vm, client, _) = loaded()
        client.devicesError = GimException.Network("connection refused")

        vm.refresh()
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertEquals(DevicesError.Network, vm.state.value.error)
        assertEquals(2, vm.state.value.devices.size)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun server_and_not_found_errors_are_mapped() {
        assertEquals(DevicesError.Server, GimException.Server(500, "boom").toDevicesError())
        assertEquals(DevicesError.NotFound, GimException.NotFound("device").toDevicesError())
        assertEquals(DevicesError.Unknown, GimException.Internal("bug").toDevicesError())
    }

    @Test
    fun not_registered_marks_this_device_removed() = runTest {
        val (vm, client, _) = loaded()
        client.devicesError = GimException.NotRegistered()

        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.removed)
        assertNull(vm.state.value.error)
    }

    @Test
    fun not_registered_from_revoke_marks_this_device_removed() = runTest {
        val (vm, client, _) = loaded()
        client.revokeError = GimException.NotRegistered()

        vm.requestRevoke(vm.state.value.devices[1])
        vm.confirmRevoke()
        advanceUntilIdle()

        assertTrue(vm.state.value.removed)
    }
}
