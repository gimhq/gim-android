package im.g.apps.feature.settings

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewRecoveryKeyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.signedIn(): Pair<FakeGimClient, String> {
        val client = FakeGimClient(backgroundScope, outboxDelays = null)
        val oldCode = client.createIdentity("Sofia Costa")
        return client to oldCode
    }

    private fun viewModel(client: FakeGimClient) =
        NewRecoveryKeyViewModel(client, FakeRecoveryKitCodec, ioDispatcher = main.dispatcher)

    private fun TestScope.created(client: FakeGimClient) = viewModel(client).apply {
        create()
        advanceUntilIdle()
    }

    private fun NewRecoveryKeyViewModel.typeEnding(ending: String) {
        onCheckChange(ending)
        confirmCheck()
    }

    @Test
    fun starts_on_the_warning() = runTest {
        val state = viewModel(signedIn().first).state.value

        assertEquals(NewRecoveryKeyStep.WARNING, state.step)
        assertNull(state.recoveryCode)
        assertFalse(state.creating)
    }

    @Test
    fun create_rotates_the_key_and_shows_the_kit() = runTest {
        val (client, oldCode) = signedIn()
        val vm = viewModel(client)

        vm.create()
        assertTrue(vm.state.value.creating)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(NewRecoveryKeyStep.KIT, state.step)
        assertFalse(state.creating)
        assertEquals(client.userId(), state.accountId)
        assertNotEquals(oldCode, state.recoveryCode)
        assertNull(state.unsynced)
        assertNull(state.error)
    }

    @Test
    fun kit_text_holds_the_account_id_and_new_key() = runTest {
        val (client, _) = signedIn()
        val vm = created(client)
        val labels = RecoveryKitLabels("gim recovery kit", "Account ID", "Recovery key", "Keep this secret.")

        val text = vm.kitText(labels)

        assertEquals(FakeRecoveryKitCodec.format(client.userId()!!, vm.state.value.recoveryCode!!, labels), text)
    }

    @Test
    fun a_failed_rotation_stays_on_the_warning_and_says_what_to_try() = runTest {
        val (client, _) = signedIn()
        client.failNext("rotateRecoveryCode", GimException.Network("offline"))
        val vm = viewModel(client)

        vm.create()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(NewRecoveryKeyStep.WARNING, state.step)
        assertFalse(state.creating)
        assertNull(state.recoveryCode)
        assertEquals(R.string.new_key_error_network, state.error)

        vm.create()
        advanceUntilIdle()
        assertEquals(NewRecoveryKeyStep.KIT, vm.state.value.step)
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_busy_server_and_other_errors_have_their_own_messages() = runTest {
        val (client, _) = signedIn()
        val vm = viewModel(client)

        client.failNext("rotateRecoveryCode", GimException.RetryLater(429, 30, "slow down"))
        vm.create()
        advanceUntilIdle()
        assertEquals(R.string.new_key_error_busy, vm.state.value.error)

        client.failNext("rotateRecoveryCode", GimException.Storage("disk"))
        vm.create()
        advanceUntilIdle()
        assertEquals(R.string.new_key_error_generic, vm.state.value.error)
    }

    @Test
    fun unsynced_servers_are_named_by_url_when_known() = runTest {
        val (client, _) = signedIn()
        val other = client.addHub("https://two.example")
        client.rotationUnsyncedHubIds = listOf(other.hubId)

        val unsynced = created(client).state.value.unsynced

        assertEquals(ServerList(urls = listOf("https://two.example"), count = 1), unsynced)
    }

    @Test
    fun unsynced_servers_are_counted_when_a_url_is_unknown() = runTest {
        val (client, _) = signedIn()
        val other = client.addHub("https://two.example")
        client.rotationUnsyncedHubIds = listOf(other.hubId, "hub-unknown")

        val unsynced = created(client).state.value.unsynced

        assertEquals(ServerList(urls = emptyList(), count = 2), unsynced)
    }

    @Test
    fun the_check_accepts_the_last_four_characters_and_forgets_the_key() = runTest {
        val (client, _) = signedIn()
        val vm = created(client)
        val ending = FakeRecoveryKitCodec.keyEnding(vm.state.value.recoveryCode!!)

        vm.continueFromKit()
        assertEquals(NewRecoveryKeyStep.CHECK, vm.state.value.step)
        vm.typeEnding(ending.lowercase())

        val state = vm.state.value
        assertEquals(NewRecoveryKeyStep.DONE, state.step)
        assertNull(state.recoveryCode)
        assertFalse(state.checkMismatch)
    }

    @Test
    fun a_check_mismatch_stays_on_the_check_and_says_so() = runTest {
        val (client, _) = signedIn()
        val vm = created(client)
        val ending = FakeRecoveryKitCodec.keyEnding(vm.state.value.recoveryCode!!)
        val wrong = if (ending == "0000") "1111" else "0000"

        vm.continueFromKit()
        assertFalse(vm.state.value.canConfirmCheck)
        vm.typeEnding(wrong)

        assertEquals(NewRecoveryKeyStep.CHECK, vm.state.value.step)
        assertTrue(vm.state.value.checkMismatch)
        vm.onCheckChange("")
        assertFalse(vm.state.value.checkMismatch)
    }

    @Test
    fun back_from_the_warning_and_done_leaves() = runTest {
        val (client, _) = signedIn()
        val warning = viewModel(client)
        warning.back()
        assertTrue(warning.state.value.left)

        val done = created(client)
        done.continueFromKit()
        done.typeEnding(FakeRecoveryKitCodec.keyEnding(done.state.value.recoveryCode!!))
        done.back()
        assertTrue(done.state.value.left)
    }

    @Test
    fun leaving_the_kit_before_the_check_asks_first() = runTest {
        val (client, _) = signedIn()
        val vm = created(client)

        vm.back()
        assertTrue(vm.state.value.confirmLeave)
        assertFalse(vm.state.value.left)
        vm.stay()
        assertFalse(vm.state.value.confirmLeave)
        assertEquals(NewRecoveryKeyStep.KIT, vm.state.value.step)

        vm.back()
        vm.leave()
        assertTrue(vm.state.value.left)
        assertNull(vm.state.value.recoveryCode)
    }

    @Test
    fun back_from_the_check_returns_to_the_kit() = runTest {
        val (client, _) = signedIn()
        val vm = created(client)

        vm.continueFromKit()
        vm.onCheckChange("AB")
        vm.back()

        assertEquals(NewRecoveryKeyStep.KIT, vm.state.value.step)
        assertEquals("", vm.state.value.check)
        assertFalse(vm.state.value.confirmLeave)
    }

    @Test
    fun back_is_ignored_while_creating() = runTest {
        val (client, _) = signedIn()
        val vm = viewModel(client)

        vm.create()
        vm.back()
        advanceUntilIdle()

        assertFalse(vm.state.value.left)
        assertEquals(NewRecoveryKeyStep.KIT, vm.state.value.step)
    }

    @Test
    fun finish_leaves() = runTest {
        val vm = viewModel(signedIn().first)

        vm.finish()

        assertTrue(vm.state.value.left)
    }
}
