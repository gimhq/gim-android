package im.g.apps.feature.settings

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply { createIdentity("Sofia Costa") }

    private fun TestScope.viewModel(client: FakeGimClient) =
        SettingsViewModel(client, ProfilePhotos(client, tmp.root), ioDispatcher = main.dispatcher).also { advanceUntilIdle() }

    @Test
    fun loads_the_session_into_the_state() = runTest {
        val client = signedIn()

        val state = viewModel(client).state.value

        assertEquals("Sofia Costa", state.displayName)
        assertEquals(client.userId(), state.accountId)
    }

    @Test
    fun refresh_picks_up_a_changed_display_name() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        client.setDisplayName("Sofia C.")
        vm.refresh()
        advanceUntilIdle()

        assertEquals("Sofia C.", vm.state.value.displayName)
    }

    @Test
    fun without_a_photo_the_header_shows_initials() = runTest {
        val client = signedIn()

        assertNull(viewModel(client).state.value.photoPath)
    }

    @Test
    fun loads_the_own_photo_into_the_avatar_folder() = runTest {
        val client = signedIn()
        client.setAvatar(File(tmp.root, "a.jpg").apply { writeBytes(byteArrayOf(1)) }.path, "image/jpeg")

        val path = viewModel(client).state.value.photoPath

        assertEquals(File(tmp.root, "gim/avatars/${client.userId()}").path, path)
        assertTrue(File(path!!).isFile)
    }

    @Test
    fun refresh_picks_up_a_new_or_removed_photo() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        client.setAvatar(File(tmp.root, "a.jpg").apply { writeBytes(byteArrayOf(1)) }.path, "image/jpeg")
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.photoPath != null)

        client.removeAvatar()
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.photoPath)
    }

    @Test
    fun loads_the_privacy_settings() = runTest {
        val client = signedIn()
        client.setReadReceipts(false)

        val state = viewModel(client).state.value

        assertFalse(state.readReceipts)
        assertTrue(state.typingIndicator)
    }

    @Test
    fun toggles_save_the_privacy_settings_right_away() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.setReadReceipts(false)
        // Optimistic: the switch moves before the call returns.
        assertFalse(vm.state.value.readReceipts)
        vm.setTypingIndicator(false)
        advanceUntilIdle()

        assertFalse(client.privacy().readReceipts)
        assertFalse(client.privacy().typingIndicators)
        assertFalse(vm.state.value.typingIndicator)
    }

    @Test
    fun a_failed_toggle_switches_back_and_says_so() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        client.failNext("setTypingIndicators", GimException.Network("offline"))

        vm.setTypingIndicator(false)
        advanceUntilIdle()

        assertTrue(vm.state.value.typingIndicator)
        assertTrue(client.privacy().typingIndicators)
        assertEquals(R.string.settings_privacy_error, vm.messages.first())
    }

    @Test
    fun a_failed_privacy_load_keeps_the_defaults() = runTest {
        val client = signedIn()
        client.setReadReceipts(false)
        client.failNext("privacy", GimException.Network("offline"))

        val state = viewModel(client).state.value

        assertEquals("Sofia Costa", state.displayName)
        assertTrue(state.readReceipts)
    }
}
