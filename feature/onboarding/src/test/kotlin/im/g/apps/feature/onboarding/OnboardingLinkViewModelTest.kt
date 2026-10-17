package im.g.apps.feature.onboarding

import app.cash.turbine.test
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.LinkSession
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakePlatformHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = InMemoryServerUrlStore()
    private var discarded = 0

    // Tests that hold waitLink use runCurrent(): advanceUntilIdle() would also run the ten-minute timeout.

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A fake client whose link calls can be held or made to fail. */
    private class LinkClient(val fake: FakeGimClient) : GimClient by fake {
        var startLinkError: GimException? = null
        var waitLinkError: GimException? = null
        var confirmLinkError: GimException? = null

        /** When set, `waitLink` suspends until it completes (or forever if never completed). */
        var waitGate: CompletableDeferred<Unit>? = null
        var waitCancelled = false
        val startedHubs = mutableListOf<List<String>>()
        val confirmations = mutableListOf<Boolean>()

        override fun startLink(hubs: List<String>): LinkSession {
            startedHubs += hubs
            startLinkError?.let { throw it }
            return fake.startLink(hubs)
        }

        override suspend fun waitLink(link: LinkSession): String {
            waitGate?.let { gate ->
                try {
                    gate.await()
                } catch (e: CancellationException) {
                    waitCancelled = true
                    throw e
                }
            }
            waitLinkError?.let { throw it }
            return fake.waitLink(link)
        }

        override suspend fun confirmLink(accepted: Boolean) {
            confirmations += accepted
            confirmLinkError?.let { throw it }
            fake.confirmLink(accepted)
        }
    }

    private val clients = mutableListOf<LinkClient>()

    private fun TestScope.manager(configure: (LinkClient) -> Unit = {}) = GimClientManager(
        GimClientFactory { _, _ ->
            LinkClient(FakeGimClient(backgroundScope, outboxDelays = null)).also(configure).also { clients += it }
        },
        FakePlatformHooks(),
        store,
    )

    private fun viewModel(manager: GimClientManager) = OnboardingViewModel(manager, SERVER_URL) {
        discarded++
        // Like AppContainer.signOut: the client may hold no session (cancelled) or be wiped already (rejected).
        runCatching { manager.signOut() }
    }

    @Test
    fun link_device_shows_the_qr_code_while_waiting() = runTest(dispatcher) {
        val vm = viewModel(manager { it.waitGate = CompletableDeferred() })

        assertTrue(vm.state.value.canCreateAccount)
        vm.linkDevice()
        runCurrent()

        val state = vm.state.value
        assertEquals(OnboardingStep.LINK_QR, state.step)
        assertTrue(state.linkQr!!.startsWith("gimlink:fake:"))
        assertFalse(state.isLoading)
        assertFalse(state.canRetryLink)
        assertNull(state.error)
        assertTrue(state.handlesBack)
        assertEquals(listOf(listOf(SERVER_URL)), clients.single().startedHubs)
        assertEquals(SERVER_URL, store.get())
    }

    @Test
    fun matching_sas_confirms_the_link_and_finishes() = runTest(dispatcher) {
        val vm = viewModel(manager())
        vm.linkDevice()
        advanceUntilIdle()

        assertEquals(OnboardingStep.LINK_SAS, vm.state.value.step)
        val sas = vm.state.value.linkSas!!
        assertTrue(sas, Regex("\\d{3} \\d{3}").matches(sas))
        assertNull(vm.state.value.linkQr)
        assertTrue(vm.state.value.handlesBack)

        vm.events.test {
            vm.confirmLink()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertEquals(listOf(true), clients.single().confirmations)
        assertEquals("u-linked", clients.single().session()?.userId)
        assertEquals(0, discarded)
    }

    @Test
    fun rejected_sas_wipes_the_client_and_returns_to_welcome() = runTest(dispatcher) {
        val manager = manager()
        val vm = viewModel(manager)
        vm.linkDevice()
        advanceUntilIdle()

        vm.rejectLink()
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()

        assertEquals(listOf(false), clients.single().confirmations)
        assertNull(clients.single().session())
        assertEquals(1, discarded)
        assertNull(manager.client.value)
        val state = vm.state.value
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertEquals(SERVER_URL, state.serverUrl)
        assertNull(state.linkSas)
        assertFalse(state.isLoading)

        // Linking again creates a new client.
        vm.linkDevice()
        advanceUntilIdle()
        assertEquals(2, clients.size)
        assertEquals(OnboardingStep.LINK_SAS, vm.state.value.step)
    }

    @Test
    fun cancel_stops_waiting_and_returns_to_welcome_with_the_client_dropped() = runTest(dispatcher) {
        val manager = manager { it.waitGate = CompletableDeferred() }
        val vm = viewModel(manager)
        vm.linkDevice()
        runCurrent()

        vm.cancelLink()
        advanceUntilIdle()

        assertTrue(clients.single().waitCancelled)
        assertEquals(1, discarded)
        assertNull(manager.client.value)
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertNull(vm.state.value.linkQr)
        assertEquals(SERVER_URL, vm.state.value.serverUrl)
    }

    @Test
    fun back_on_the_qr_code_cancels_the_link() = runTest(dispatcher) {
        val vm = viewModel(manager { it.waitGate = CompletableDeferred() })
        vm.linkDevice()
        runCurrent()

        vm.back()
        advanceUntilIdle()

        assertTrue(clients.single().waitCancelled)
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertEquals(1, discarded)
    }

    @Test
    fun back_on_the_sas_does_nothing() = runTest(dispatcher) {
        val vm = viewModel(manager())
        vm.linkDevice()
        advanceUntilIdle()
        val before = vm.state.value

        vm.back()
        advanceUntilIdle()

        assertEquals(before, vm.state.value)
        assertEquals(0, discarded)
    }

    @Test
    fun wait_times_out_after_ten_minutes_then_retry_shows_a_new_code() = runTest(dispatcher) {
        val vm = viewModel(manager { it.waitGate = CompletableDeferred() })
        vm.linkDevice()
        runCurrent()
        val firstQr = vm.state.value.linkQr

        advanceTimeBy(OnboardingViewModel.LINK_TIMEOUT.inWholeMilliseconds - 1)
        assertEquals(firstQr, vm.state.value.linkQr)
        advanceTimeBy(2)
        advanceUntilIdle()

        assertEquals(OnboardingStep.LINK_QR, vm.state.value.step)
        assertNull(vm.state.value.linkQr)
        assertEquals(OnboardingError.LinkTimedOut, vm.state.value.error)
        assertTrue(vm.state.value.canRetryLink)
        assertTrue(clients.single().waitCancelled)

        clients.single().waitGate = null
        vm.retryLink()
        advanceUntilIdle()

        assertEquals(2, clients.single().startedHubs.size)
        assertEquals(OnboardingStep.LINK_SAS, vm.state.value.step)
        assertNull(vm.state.value.error)
    }

    @Test
    fun wait_errors_are_mapped_and_allow_a_retry() = runTest(dispatcher) {
        val cases = listOf(
            GimException.Network("offline") to OnboardingError.CannotReachServer,
            GimException.RetryLater(429, 30, "slow down") to OnboardingError.TryAgainLater(30),
            GimException.RetryLater(503, 0, "remote hub down") to OnboardingError.TryAgainLater(1),
            GimException.Crypto("bundle does not verify") to OnboardingError.LinkFailed,
            GimException.InvalidInput("link") to OnboardingError.LinkFailed,
        )
        for ((error, expected) in cases) {
            val vm = viewModel(manager { it.waitLinkError = error })
            vm.linkDevice()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(error.toString(), expected, state.error)
            assertEquals(OnboardingStep.LINK_QR, state.step)
            assertNull(state.linkQr)
            assertTrue(state.canRetryLink)
        }
    }

    @Test
    fun suspended_wait_shows_account_suspended_without_a_retry_or_discarding_the_client() = runTest(dispatcher) {
        val vm = viewModel(manager { it.waitLinkError = GimException.UserSuspended("") })
        vm.linkDevice()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(OnboardingError.AccountSuspended, state.error)
        assertEquals(OnboardingStep.LINK_QR, state.step)
        assertFalse(state.canRetryLink)
        vm.retryLink()
        advanceUntilIdle()
        assertEquals(1, clients.single().startedHubs.size)
        assertEquals(0, discarded)
    }

    @Test
    fun start_link_failure_stays_on_welcome_with_the_error() = runTest(dispatcher) {
        val vm = viewModel(manager { it.startLinkError = GimException.RetryLater(429, 12, "throttled") })
        vm.linkDevice()
        advanceUntilIdle()

        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertEquals(OnboardingError.TryAgainLater(12), vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun link_device_needs_a_valid_server_url() = runTest(dispatcher) {
        val vm = viewModel(manager())
        vm.onServerUrlChange("not a url")
        vm.linkDevice()
        advanceUntilIdle()

        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertTrue(clients.isEmpty())
    }

    @Test
    fun confirm_failure_keeps_the_sas_so_the_user_can_retry_or_reject() = runTest(dispatcher) {
        val vm = viewModel(manager())
        vm.linkDevice()
        advanceUntilIdle()
        val sas = vm.state.value.linkSas
        clients.single().confirmLinkError = GimException.Network("offline")

        vm.confirmLink()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(OnboardingStep.LINK_SAS, state.step)
        assertEquals(sas, state.linkSas)
        assertEquals(OnboardingError.CannotReachServer, state.error)
        assertFalse(state.isLoading)

        vm.rejectLink()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertEquals(1, discarded)
    }

    @Test
    fun default_discard_signs_out_quietly_even_without_a_session() = runTest(dispatcher) {
        val manager = manager { it.waitGate = CompletableDeferred() }
        val vm = OnboardingViewModel(manager, SERVER_URL)
        vm.linkDevice()
        runCurrent()
        assertNotNull(manager.client.value)

        vm.cancelLink()
        advanceUntilIdle()

        assertNull(manager.client.value)
        assertNull(store.get())
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertFalse(vm.state.value.isLoading)
    }

    private companion object {
        const val SERVER_URL = "https://gim.example.eu"
    }
}
