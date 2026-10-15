package im.g.apps.feature.onboarding

import app.cash.turbine.test
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
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
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = InMemoryServerUrlStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun manager(factory: GimClientFactory) = GimClientManager(factory, FakePlatformHooks(), store)

    private fun TestScope.fakeFactory(configure: (FakeGimClient) -> Unit = {}) = FakeGimClientFactory { _, _ ->
        FakeGimClient(backgroundScope, outboxDelays = null).also(configure)
    }

    /** A fake client whose `register` throws [error]. */
    private fun TestScope.failingRegisterFactory(error: GimException) = GimClientFactory { _, _ ->
        object : GimClient by FakeGimClient(backgroundScope, outboxDelays = null) {
            override suspend fun register(handle: String, displayName: String) = throw error
        }
    }

    private fun OnboardingViewModel.fillValid() {
        onServerUrlChange(SERVER_URL)
        onHandleChange("sofia")
        onDisplayNameChange("Sofia Costa")
    }

    @Test
    fun initial_state_is_empty_and_cannot_submit() = runTest(dispatcher) {
        val state = OnboardingViewModel(manager(fakeFactory())).state.value
        assertEquals(OnboardingUiState(), state)
        assertFalse(state.canSubmit)
    }

    @Test
    fun valid_default_server_url_is_prefilled_trimmed_and_submittable() = runTest(dispatcher) {
        val factory = fakeFactory()
        val vm = OnboardingViewModel(manager(factory), defaultServerUrl = " $EMULATOR_URL ")
        assertEquals(EMULATOR_URL, vm.state.value.serverUrl)
        assertNull(vm.state.value.serverUrlError)
        vm.onHandleChange("sofia")
        vm.onDisplayNameChange("Sofia Costa")
        assertTrue(vm.state.value.canSubmit)

        vm.events.test {
            vm.submit()
            assertTrue(awaitItem() is OnboardingEvent.Registered)
        }
        assertEquals(EMULATOR_URL, store.get())
    }

    @Test
    fun empty_default_server_url_keeps_field_empty() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), defaultServerUrl = "")
        assertEquals(OnboardingUiState(), vm.state.value)
    }

    @Test
    fun invalid_default_server_url_is_shown_and_flagged() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), defaultServerUrl = "gim.example.eu")
        assertEquals("gim.example.eu", vm.state.value.serverUrl)
        assertEquals(FieldError.InvalidServerUrl, vm.state.value.serverUrlError)
        vm.onHandleChange("sofia")
        vm.onDisplayNameChange("Sofia Costa")
        assertFalse(vm.state.value.canSubmit)
        vm.onServerUrlChange(SERVER_URL)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun server_url_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()))
        val valid = listOf("https://gim.example.eu", "http://10.0.2.2:8080", "HTTPS://gim.example.eu/path", " https://gim.example.eu ")
        val invalid = listOf("gim.example.eu", "ftp://gim.example.eu", "https://", "https://exa mple.eu", "https:///path")
        for (url in valid) {
            vm.onServerUrlChange(url)
            assertNull(url, vm.state.value.serverUrlError)
        }
        for (url in invalid) {
            vm.onServerUrlChange(url)
            assertEquals(url, FieldError.InvalidServerUrl, vm.state.value.serverUrlError)
        }
        vm.onServerUrlChange("")
        assertNull(vm.state.value.serverUrlError)
    }

    @Test
    fun handle_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()))
        val valid = listOf("abc", "sofia_costa", "s.costa", "a1b2c3", "x".repeat(32))
        val invalid = listOf("ab", "x".repeat(33), "sofia-costa", "sofia costa", "sofía", "sofia!")
        for (handle in valid) {
            vm.onHandleChange(handle)
            assertNull(handle, vm.state.value.handleError)
        }
        for (handle in invalid) {
            vm.onHandleChange(handle)
            assertEquals(handle, FieldError.InvalidHandle, vm.state.value.handleError)
        }
        vm.onHandleChange("")
        assertNull(vm.state.value.handleError)
    }

    @Test
    fun handle_is_normalized_to_lowercase_without_at_sign() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()))
        vm.onHandleChange(" @Sofia.Costa ")
        assertEquals("sofia.costa", vm.state.value.handle)
        assertNull(vm.state.value.handleError)
    }

    @Test
    fun display_name_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()))
        for (name in listOf("S", "  Sofia  ", "x".repeat(64), "  " + "x".repeat(64) + "  ")) {
            vm.onDisplayNameChange(name)
            assertNull(name, vm.state.value.displayNameError)
        }
        for (name in listOf("   ", "x".repeat(65))) {
            vm.onDisplayNameChange(name)
            assertEquals(name, FieldError.InvalidDisplayName, vm.state.value.displayNameError)
        }
    }

    @Test
    fun can_submit_only_when_all_fields_are_valid() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()))
        vm.onServerUrlChange(SERVER_URL)
        assertFalse(vm.state.value.canSubmit)
        vm.onHandleChange("sofia")
        assertFalse(vm.state.value.canSubmit)
        vm.onDisplayNameChange("Sofia")
        assertTrue(vm.state.value.canSubmit)
        vm.onHandleChange("so")
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun submit_success_registers_trimmed_values_and_emits_event() = runTest(dispatcher) {
        val factory = fakeFactory()
        val vm = OnboardingViewModel(manager(factory))
        vm.onServerUrlChange(" $SERVER_URL ")
        vm.onHandleChange("Sofia")
        vm.onDisplayNameChange("  Sofia Costa ")

        vm.events.test {
            vm.submit()
            assertTrue(vm.state.value.isLoading)
            val event = awaitItem() as OnboardingEvent.Registered
            assertEquals("sofia", event.session.handle)
            assertEquals("Sofia Costa", event.session.displayName)
        }
        assertFalse(vm.state.value.isLoading)
        assertEquals(SERVER_URL, store.get())
        assertEquals(1, factory.created.size)
        assertEquals("sofia", factory.created.single().session()?.handle)
    }

    @Test
    fun double_submit_is_ignored_while_loading() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var creates = 0
        val vm = OnboardingViewModel(
            manager { _, _ ->
                creates++
                gate.await()
                FakeGimClient(backgroundScope, outboxDelays = null)
            },
        )
        vm.fillValid()

        vm.events.test {
            vm.submit()
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoading)
            vm.submit()
            vm.onHandleChange("other")
            advanceUntilIdle()
            assertEquals("sofia", vm.state.value.handle)

            gate.complete(Unit)
            assertTrue(awaitItem() is OnboardingEvent.Registered)
            expectNoEvents()
        }
        assertEquals(1, creates)
    }

    @Test
    fun network_error_maps_to_cannot_reach_server() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingRegisterFactory(GimException.Network("timeout"))))
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun network_error_while_connecting_maps_to_cannot_reach_server() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { _, _ -> throw GimException.Network("unreachable") })
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
    }

    @Test
    fun conflict_maps_to_handle_taken_until_handle_changes() = runTest(dispatcher) {
        val vm = OnboardingViewModel(
            manager(fakeFactory { it.addDirectoryUser(UserProfile("u-sofia", "sofia", "Sofia")) }),
        )
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(FieldError.HandleTaken, vm.state.value.handleError)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.canSubmit)

        vm.onHandleChange("sofia2")
        assertNull(vm.state.value.handleError)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun invalid_input_from_connect_maps_to_server_url_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { _, _ -> throw GimException.InvalidInput("server_url: rejected") })
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(FieldError.InvalidServerUrl, vm.state.value.serverUrlError)
        assertNull(vm.state.value.handleError)
    }

    @Test
    fun invalid_input_from_register_maps_to_handle_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingRegisterFactory(GimException.InvalidInput("handle: reserved"))))
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(FieldError.InvalidHandle, vm.state.value.handleError)
        assertNull(vm.state.value.serverUrlError)
    }

    @Test
    fun other_errors_map_to_generic() = runTest(dispatcher) {
        val errors = listOf(
            GimException.Server(500, "boom"),
            GimException.Internal("bug"),
            GimException.NotImplemented(),
            GimException.Storage("disk"),
        )
        for (error in errors) {
            val vm = OnboardingViewModel(manager(failingRegisterFactory(error)))
            vm.fillValid()
            vm.submit()
            advanceUntilIdle()
            assertEquals(error.toString(), OnboardingError.Generic, vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
        }
    }

    @Test
    fun editing_a_field_clears_the_general_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingRegisterFactory(GimException.Internal("bug"))))
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertEquals(OnboardingError.Generic, vm.state.value.error)
        vm.onDisplayNameChange("Sofia")
        assertNull(vm.state.value.error)
    }

    private companion object {
        const val SERVER_URL = "https://gim.example.eu"
        const val EMULATOR_URL = "http://10.0.2.2:8080"
    }
}
