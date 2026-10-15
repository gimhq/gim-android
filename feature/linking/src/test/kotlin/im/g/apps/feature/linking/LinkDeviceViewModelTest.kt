package im.g.apps.feature.linking

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PendingApproval
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDeviceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A signed-in fake whose approval calls can be held or made to fail. */
    private class ApprovingClient(val fake: FakeGimClient) : GimClient by fake {
        var approveError: GimException? = null
        var confirmError: GimException? = null
        var approveGate: CompletableDeferred<Unit>? = null
        val approved = mutableListOf<String>()

        override suspend fun approveLink(qrString: String): PendingApproval {
            approved += qrString
            approveGate?.await()
            approveError?.let { throw it }
            return fake.approveLink(qrString)
        }

        override suspend fun confirmApproval(approval: PendingApproval, accepted: Boolean) {
            confirmError?.let { throw it }
            fake.confirmApproval(approval, accepted)
        }
    }

    private suspend fun TestScope.signedInClient(): ApprovingClient {
        val fake = FakeGimClient(backgroundScope, outboxDelays = null)
        fake.createIdentity("Sofia Costa")
        return ApprovingClient(fake)
    }

    /** A code the fake accepts: the QR string of another fake's link session. */
    private fun TestScope.linkCode(): String = FakeGimClient(backgroundScope, outboxDelays = null).startLink(emptyList()).qrString()

    @Test
    fun scanned_code_shows_the_sas_and_confirming_adds_the_device() = runTest(dispatcher) {
        val client = signedInClient()
        val vm = LinkDeviceViewModel(client)

        vm.onCodeScanned(linkCode())
        assertEquals(LinkDeviceStep.APPROVING, vm.state.value.step)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(LinkDeviceStep.CONFIRM, state.step)
        assertTrue(state.sas!!, Regex("\\d{3} \\d{3}").matches(state.sas!!))
        val deviceId = state.deviceId!!

        vm.confirm(true)
        assertEquals(LinkDeviceStep.CONFIRMING, vm.state.value.step)
        advanceUntilIdle()

        assertEquals(LinkDeviceStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.linked)
        val added = client.devices().single { it.deviceId == deviceId }
        assertFalse(added.revoked)
        assertEquals(2, client.devices().size)
    }

    @Test
    fun rejecting_the_sas_revokes_the_device() = runTest(dispatcher) {
        val client = signedInClient()
        val vm = LinkDeviceViewModel(client)
        vm.onCodeScanned(linkCode())
        advanceUntilIdle()
        val deviceId = vm.state.value.deviceId

        vm.confirm(false)
        advanceUntilIdle()

        assertEquals(LinkDeviceStep.DONE, vm.state.value.step)
        assertFalse(vm.state.value.linked)
        assertTrue(client.devices().single { it.deviceId == deviceId }.revoked)
    }

    @Test
    fun pasted_code_is_trimmed_and_approved() = runTest(dispatcher) {
        val client = signedInClient()
        val vm = LinkDeviceViewModel(client)
        assertFalse(vm.state.value.canSubmitPastedCode)
        val code = linkCode()

        vm.onPastedCodeChange("  $code\n")
        assertTrue(vm.state.value.canSubmitPastedCode)
        vm.submitPastedCode()
        advanceUntilIdle()

        assertEquals(listOf(code), client.approved)
        assertEquals(LinkDeviceStep.CONFIRM, vm.state.value.step)
    }

    @Test
    fun invalid_code_shows_an_error_and_the_same_scan_is_not_retried() = runTest(dispatcher) {
        val client = signedInClient()
        val vm = LinkDeviceViewModel(client)

        vm.onCodeScanned("https://example.eu/not-a-link")
        advanceUntilIdle()

        assertEquals(LinkDeviceStep.SCAN, vm.state.value.step)
        assertEquals(LinkDeviceError.InvalidCode, vm.state.value.error)

        // The camera keeps reporting the same code; it is not sent again.
        vm.onCodeScanned("https://example.eu/not-a-link")
        advanceUntilIdle()
        assertEquals(1, client.approved.size)

        // Typing clears the error.
        vm.onPastedCodeChange("x")
        assertNull(vm.state.value.error)

        val code = linkCode()
        vm.onCodeScanned(code)
        advanceUntilIdle()
        assertEquals(LinkDeviceStep.CONFIRM, vm.state.value.step)
    }

    @Test
    fun approve_errors_are_mapped_and_scanning_resumes() = runTest(dispatcher) {
        val cases = listOf(
            GimException.RetryLater(429, 45, "throttled") to LinkDeviceError.TryAgainLater(45),
            GimException.RetryLater(503, 0, "remote hub down") to LinkDeviceError.TryAgainLater(1),
            GimException.Network("offline") to LinkDeviceError.CannotReachServer,
            GimException.Crypto("bad key") to LinkDeviceError.Generic,
        )
        for ((error, expected) in cases) {
            val client = signedInClient().apply { approveError = error }
            val vm = LinkDeviceViewModel(client)
            val code = linkCode()

            vm.onCodeScanned(code)
            advanceUntilIdle()
            assertEquals(error.toString(), expected, vm.state.value.error)
            assertEquals(LinkDeviceStep.SCAN, vm.state.value.step)

            // Not an invalid code: the same code may be scanned again once the hub recovers.
            client.approveError = null
            vm.onCodeScanned(code)
            advanceUntilIdle()
            assertEquals(LinkDeviceStep.CONFIRM, vm.state.value.step)
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun scans_while_approving_are_ignored() = runTest(dispatcher) {
        val client = signedInClient().apply { approveGate = CompletableDeferred() }
        val vm = LinkDeviceViewModel(client)
        val code = linkCode()

        vm.onCodeScanned(code)
        advanceUntilIdle()
        vm.onCodeScanned(code)
        vm.onPastedCodeChange(code)
        vm.submitPastedCode()
        advanceUntilIdle()

        assertEquals(1, client.approved.size)
        assertEquals(LinkDeviceStep.APPROVING, vm.state.value.step)
        assertEquals("", vm.state.value.pastedCode)
        client.approveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(LinkDeviceStep.CONFIRM, vm.state.value.step)
    }

    @Test
    fun confirm_failure_keeps_the_sas_for_another_try() = runTest(dispatcher) {
        val client = signedInClient()
        val vm = LinkDeviceViewModel(client)
        vm.onCodeScanned(linkCode())
        advanceUntilIdle()
        client.confirmError = GimException.RetryLater(429, 5, "throttled")

        vm.confirm(true)
        advanceUntilIdle()

        assertEquals(LinkDeviceStep.CONFIRM, vm.state.value.step)
        assertEquals(LinkDeviceError.TryAgainLater(5), vm.state.value.error)

        client.confirmError = null
        vm.confirm(true)
        advanceUntilIdle()
        assertEquals(LinkDeviceStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.linked)
    }
}
