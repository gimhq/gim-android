package im.g.apps.feature.settings

import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply { createIdentity("Sofia Costa") }

    private fun TestScope.viewModel(client: FakeGimClient) =
        ProfileViewModel(client, ProfilePhotos(client, tmp.root), ioDispatcher = main.dispatcher).also { advanceUntilIdle() }

    @Test
    fun loads_the_session_into_the_state() = runTest {
        val client = signedIn()
        client.claimHandle("sofia")

        val state = viewModel(client).state.value

        assertEquals("Sofia Costa", state.displayName)
        assertEquals("sofia", state.username)
        assertEquals(client.userId(), state.accountId)
    }

    @Test
    fun rename_trims_saves_reloads_and_closes_the_editor() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.editName()
        vm.saveName("  Sofia C.  ")
        advanceUntilIdle()

        assertEquals("Sofia C.", client.session()!!.displayName)
        assertEquals("Sofia C.", vm.state.value.displayName)
        assertNull(vm.state.value.editor)
        assertEquals(ProfileEvent.Message(R.string.profile_name_saved), vm.events.first())
    }

    @Test
    fun blank_or_too_long_name_is_rejected_without_a_call() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        vm.editName()

        vm.saveName("   ")
        advanceUntilIdle()
        assertEquals(ProfileFieldError.NAME_INVALID, vm.state.value.nameError)

        vm.saveName("x".repeat(65))
        advanceUntilIdle()
        assertEquals(ProfileFieldError.NAME_INVALID, vm.state.value.nameError)

        assertEquals("Sofia Costa", client.session()!!.displayName)
        assertEquals(ProfileEditor.NAME, vm.state.value.editor)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun claim_normalizes_and_saves_the_username() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.editUsername()
        vm.saveUsername(" @Sofia_C ")
        advanceUntilIdle()

        assertEquals("sofia_c", client.session()!!.handle)
        assertEquals("sofia_c", vm.state.value.username)
        assertNull(vm.state.value.editor)
        assertEquals(ProfileEvent.Message(R.string.profile_username_saved), vm.events.first())
    }

    @Test
    fun change_claims_the_new_username() = runTest {
        val client = signedIn()
        client.claimHandle("sofia")
        val vm = viewModel(client)

        vm.editUsername()
        vm.saveUsername("sofia_new")
        advanceUntilIdle()

        assertEquals("sofia_new", vm.state.value.username)
    }

    @Test
    fun invalid_username_is_rejected_locally() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        vm.editUsername()

        vm.saveUsername("ab")
        advanceUntilIdle()
        assertEquals(ProfileFieldError.USERNAME_INVALID, vm.state.value.usernameError)

        vm.saveUsername("no spaces")
        advanceUntilIdle()
        assertEquals(ProfileFieldError.USERNAME_INVALID, vm.state.value.usernameError)

        assertEquals("", client.session()!!.handle)
        assertEquals(ProfileEditor.USERNAME, vm.state.value.editor)
    }

    @Test
    fun taken_username_shows_the_taken_message() = runTest {
        val client = signedIn()
        client.addDirectoryUser(UserProfile(userId = "u-other", handle = "lukas", displayName = "Lukas"))
        val vm = viewModel(client)
        vm.editUsername()

        vm.saveUsername("lukas")
        advanceUntilIdle()

        assertEquals(ProfileFieldError.USERNAME_TAKEN, vm.state.value.usernameError)
        assertEquals(ProfileEditor.USERNAME, vm.state.value.editor)
        assertNull(vm.state.value.username)
    }

    @Test
    fun remove_releases_the_username_and_clears_it() = runTest {
        val client = signedIn()
        client.claimHandle("sofia")
        val vm = viewModel(client)

        vm.askRemoveUsername()
        assertEquals(ProfileEditor.REMOVE_USERNAME, vm.state.value.editor)
        vm.removeUsername()
        advanceUntilIdle()

        assertEquals("", client.session()!!.handle)
        assertNull(vm.state.value.username)
        assertNull(vm.state.value.editor)
        assertEquals(ProfileEvent.Message(R.string.profile_username_removed), vm.events.first())
    }

    @Test
    fun copy_event_carries_the_account_id() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.copyAccountId()

        assertEquals(ProfileEvent.CopyAccountId(client.userId()!!), vm.events.first())
    }

    @Test
    fun cancel_closes_the_editor_and_clears_errors() = runTest {
        val vm = viewModel(signedIn())
        vm.editName()
        vm.saveName("")

        vm.cancelEdit()

        assertNull(vm.state.value.editor)
        assertNull(vm.state.value.nameError)
    }
}
