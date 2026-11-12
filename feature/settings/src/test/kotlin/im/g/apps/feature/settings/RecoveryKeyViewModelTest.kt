package im.g.apps.feature.settings

import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class RecoveryKeyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.signedIn(): Pair<FakeGimClient, String> {
        val client = FakeGimClient(backgroundScope, outboxDelays = null)
        return client to client.createIdentity("Sofia Costa")
    }

    private fun TestScope.viewModel(client: FakeGimClient) =
        RecoveryKeyViewModel(client, FakeRecoveryKitCodec, ioDispatcher = main.dispatcher).apply {
            refresh()
            advanceUntilIdle()
        }

    @Test
    fun a_held_key_stays_hidden_until_authentication_succeeds() = runTest {
        val (client, code) = signedIn()
        val vm = viewModel(client)

        assertEquals(RecoveryKeyStatus.LOCKED, vm.state.value.status)
        assertNull(vm.state.value.recoveryCode)

        vm.onAuthResult(DeviceAuthResult.CANCELLED)
        advanceUntilIdle()
        assertEquals(RecoveryKeyStatus.LOCKED, vm.state.value.status)
        assertNull(vm.state.value.authError)

        vm.onAuthResult(DeviceAuthResult.SUCCESS)
        advanceUntilIdle()
        assertEquals(RecoveryKeyStatus.SHOWN, vm.state.value.status)
        assertEquals(code, vm.state.value.recoveryCode)
        assertEquals(client.userId(), vm.state.value.accountId)
    }

    @Test
    fun failed_or_impossible_authentication_says_why() = runTest {
        val vm = viewModel(signedIn().first)

        vm.onAuthResult(DeviceAuthResult.FAILED)
        assertEquals(R.string.recovery_key_auth_failed, vm.state.value.authError)
        vm.onAuthResult(DeviceAuthResult.NO_LOCK)
        assertEquals(R.string.recovery_key_no_lock, vm.state.value.authError)
        vm.startAuth()
        assertNull(vm.state.value.authError)
        assertNull(vm.state.value.recoveryCode)
    }

    @Test
    fun hide_drops_the_key_and_the_qr_code() = runTest {
        val vm = viewModel(signedIn().first)
        vm.onAuthResult(DeviceAuthResult.SUCCESS)
        advanceUntilIdle()
        vm.toggleQr()
        assertTrue(vm.state.value.showQr)

        vm.hide()
        assertEquals(RecoveryKeyStatus.LOCKED, vm.state.value.status)
        assertNull(vm.state.value.recoveryCode)
        assertFalse(vm.state.value.showQr)

        // A refresh while shown (the screen started again) keeps showing it; after hide it stays locked.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(RecoveryKeyStatus.LOCKED, vm.state.value.status)
    }

    @Test
    fun the_kit_text_holds_the_account_id_and_the_key() = runTest {
        val (client, code) = signedIn()
        val vm = viewModel(client)
        vm.onAuthResult(DeviceAuthResult.SUCCESS)
        advanceUntilIdle()
        val labels = RecoveryKitLabels("Kit", "Account ID", "Recovery key", "Note")

        assertEquals(FakeRecoveryKitCodec.format(client.userId()!!, code, labels), vm.kitText(labels))
    }

    @Test
    fun a_device_without_the_key_offers_a_new_one_which_is_then_viewable() = runTest {
        val (client, _) = signedIn()
        client.heldRecoveryCode = null
        val vm = viewModel(client)
        assertEquals(RecoveryKeyStatus.MISSING, vm.state.value.status)
        vm.onAuthResult(DeviceAuthResult.SUCCESS)
        assertEquals(RecoveryKeyStatus.MISSING, vm.state.value.status)

        val rotated = client.rotateRecoveryCode().recoveryCode
        vm.refresh()
        advanceUntilIdle()
        assertEquals(RecoveryKeyStatus.LOCKED, vm.state.value.status)
        vm.onAuthResult(DeviceAuthResult.SUCCESS)
        advanceUntilIdle()
        assertEquals(rotated, vm.state.value.recoveryCode)
    }

    @Test
    fun signed_out_is_an_error() = runTest {
        val vm = viewModel(FakeGimClient(backgroundScope, outboxDelays = null))
        assertEquals(RecoveryKeyStatus.ERROR, vm.state.value.status)
    }
}
