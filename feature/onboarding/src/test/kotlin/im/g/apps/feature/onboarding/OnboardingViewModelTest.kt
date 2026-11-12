package im.g.apps.feature.onboarding

import app.cash.turbine.test
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.RecoveryKitCodec
import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
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

    /** A fake client whose `createIdentity` throws. */
    private fun TestScope.failingFactory(createIdentity: GimException? = null) = GimClientFactory { _, _ ->
        val fake = FakeGimClient(backgroundScope, outboxDelays = null)
        object : GimClient by fake {
            override suspend fun createIdentity(displayName: String): String =
                createIdentity?.let { throw it } ?: fake.createIdentity(displayName)
        }
    }

    /**
     * Models gim-core's onboarding retry: a hub in [down] fails `createIdentity` with `Network`, and the call discards
     * what the attempt stored, so the same client can call it again; every successful call creates a new user and
     * returns its code (recorded in [codes]). [signOuts] counts `signOut` calls, which a retry must not need.
     */
    private class CoreLocalState(val down: MutableSet<String>) {
        val clients = mutableListOf<String>()
        val codes = mutableListOf<String>()
        var signOuts = 0
    }

    private fun TestScope.coreFactory(local: CoreLocalState) = GimClientFactory { config, _ ->
        val fake = FakeGimClient(backgroundScope, outboxDelays = null)
        local.clients += config.serverUrl
        object : GimClient by fake {
            override suspend fun createIdentity(displayName: String): String {
                if (config.serverUrl in local.down) throw GimException.Network("connection refused")
                return fake.createIdentity(displayName).also { local.codes += it }
            }

            override suspend fun signOut() {
                local.signOuts++
                fake.signOut()
            }
        }
    }

    /** Welcome -> display name -> recovery kit; returns once the kit is shown. */
    private fun TestScope.toRecoveryKit(vm: OnboardingViewModel) {
        vm.onServerUrlChange(SERVER_URL)
        vm.createAccount()
        vm.onDisplayNameChange("Sofia Costa")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
    }

    @Test
    fun initial_state_is_intro_and_cannot_create() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec)
        val state = vm.state.value
        assertEquals(OnboardingUiState(), state)
        assertEquals(OnboardingStep.INTRO, state.step)
        assertFalse(state.canCreateAccount)
        assertFalse(state.handlesBack)
        assertFalse(state.hasDefaultServer)
        assertTrue(state.showServerField)

        // Nothing past the intro works yet, and back does not leave it.
        vm.onServerUrlChange(SERVER_URL)
        vm.createAccount()
        vm.back()
        assertEquals(OnboardingStep.INTRO, vm.state.value.step)

        vm.finishIntro()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertFalse(vm.state.value.handlesBack)
        vm.finishIntro()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun valid_default_server_url_is_prefilled_trimmed_and_usable() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, defaultServerUrl = " $EMULATOR_URL ")
        vm.finishIntro()
        assertEquals(EMULATOR_URL, vm.state.value.serverUrl)
        assertNull(vm.state.value.serverUrlError)
        assertTrue(vm.state.value.canCreateAccount)
        assertTrue(vm.state.value.hasDefaultServer)
        assertFalse(vm.state.value.showServerField)
    }

    @Test
    fun valid_default_server_goes_straight_to_the_display_name() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)
        assertEquals(SERVER_URL, vm.state.value.serverUrl)
        assertFalse(vm.state.value.showServerField)
    }

    @Test
    fun another_server_shows_the_field_prefilled_and_an_invalid_edit_blocks_create() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, SERVER_URL)
        vm.useAnotherServer()
        assertFalse("ignored on the intro", vm.state.value.showServerField)
        vm.finishIntro()

        vm.useAnotherServer()
        assertTrue(vm.state.value.showServerField)
        assertEquals(SERVER_URL, vm.state.value.serverUrl)
        assertTrue(vm.state.value.hasDefaultServer)

        vm.onServerUrlChange("gim.example.eu")
        assertFalse(vm.state.value.canCreateAccount)
        vm.createAccount()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)

        vm.onServerUrlChange(EMULATOR_URL)
        vm.createAccount()
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)
        vm.back()
        assertTrue("the field stays open", vm.state.value.showServerField)
    }

    @Test
    fun blank_default_server_shows_the_field() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, defaultServerUrl = "  ")
        vm.finishIntro()
        assertFalse(vm.state.value.hasDefaultServer)
        assertTrue(vm.state.value.showServerField)
        assertEquals("", vm.state.value.serverUrl)
        vm.createAccount()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun invalid_default_server_url_is_shown_and_flagged() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, defaultServerUrl = "gim.example.eu")
        vm.finishIntro()
        assertFalse(vm.state.value.hasDefaultServer)
        assertTrue(vm.state.value.showServerField)
        assertEquals(FieldError.InvalidServerUrl, vm.state.value.serverUrlError)
        vm.createAccount()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        vm.onServerUrlChange(SERVER_URL)
        vm.createAccount()
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)
    }

    @Test
    fun server_url_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec)
        vm.finishIntro()
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
    fun back_returns_from_display_name_to_welcome() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)
        assertTrue(vm.state.value.handlesBack)
        vm.back()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun display_name_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        for (name in listOf("S", "  Sofia  ", "x".repeat(64), "  " + "x".repeat(64) + "  ")) {
            vm.onDisplayNameChange(name)
            assertNull(name, vm.state.value.displayNameError)
            assertTrue(name, vm.state.value.canSubmitDisplayName)
        }
        for (name in listOf("   ", "x".repeat(65))) {
            vm.onDisplayNameChange(name)
            assertEquals(name, FieldError.InvalidDisplayName, vm.state.value.displayNameError)
            assertFalse(name, vm.state.value.canSubmitDisplayName)
        }
    }

    @Test
    fun create_shows_recovery_kit_then_check_and_finishes() = runTest(dispatcher) {
        val factory = fakeFactory()
        val vm = OnboardingViewModel(manager(factory), FakeRecoveryKitCodec)
        vm.finishIntro()
        vm.onServerUrlChange(" $SERVER_URL ")
        vm.createAccount()
        vm.onDisplayNameChange("  Sofia Costa ")

        vm.submitDisplayName()
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
        val code = FakeGimClient.fakeRecoveryCode(1)
        assertEquals(code, vm.state.value.recoveryCode)
        assertEquals(SERVER_URL, store.get())
        val client = factory.created.single()
        assertEquals(client.userId(), vm.state.value.accountId)
        assertEquals("Sofia Costa", client.session()?.displayName)

        // The kit must be passed; back does not leave it.
        assertTrue(vm.state.value.handlesBack)
        vm.back()
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
        vm.continueFromKit()
        assertEquals(OnboardingStep.RECOVERY_CHECK, vm.state.value.step)
        assertTrue(vm.state.value.handlesBack)

        // Lower case with a dash and spaces still matches the last four symbols.
        val ending = code.replace("-", "").takeLast(4)
        vm.onKitCheckChange(" ${ending.take(2).lowercase()}-${ending.drop(2).lowercase()} ")
        vm.events.test {
            vm.confirmKitCheck()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertNull(vm.state.value.recoveryCode)
        assertNull(vm.state.value.kitCheckError)
        assertFalse(vm.state.value.isLoading)
        assertEquals(1, factory.created.size)
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
            FakeRecoveryKitCodec,
            SERVER_URL,
        )
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        vm.submitDisplayName()
        vm.onDisplayNameChange("Other")
        vm.back()
        advanceUntilIdle()
        assertEquals("Sofia", vm.state.value.displayName)
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
        assertEquals(1, creates)
    }

    @Test
    fun network_error_on_create_maps_to_cannot_reach_server_and_allows_retry() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingFactory(createIdentity = GimException.Network("timeout"))), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingStep.DISPLAY_NAME, vm.state.value.step)
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.canSubmitDisplayName)
    }

    @Test
    fun retry_after_network_error_on_create_shows_the_recovery_code() = runTest(dispatcher) {
        val local = CoreLocalState(mutableSetOf(SERVER_URL))
        val vm = OnboardingViewModel(manager(coreFactory(local)), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)

        local.down.clear()
        vm.submitDisplayName()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
        assertEquals(local.codes.single(), vm.state.value.recoveryCode)
        // The same client retries: no sign-out, no second client.
        assertEquals(listOf(SERVER_URL), local.clients)
        assertEquals(0, local.signOuts)
    }

    @Test
    fun retry_with_another_server_url_after_network_error_shows_the_recovery_code() = runTest(dispatcher) {
        val local = CoreLocalState(mutableSetOf(UNREACHABLE_URL))
        val vm = OnboardingViewModel(manager(coreFactory(local)), FakeRecoveryKitCodec, UNREACHABLE_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)

        vm.back()
        vm.onServerUrlChange(SERVER_URL)
        vm.createAccount()
        assertEquals("Sofia", vm.state.value.displayName)
        vm.submitDisplayName()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
        assertEquals(local.codes.single(), vm.state.value.recoveryCode)
        assertEquals(listOf(UNREACHABLE_URL, SERVER_URL), local.clients)
        // The client for the first URL is signed out before the next one opens the same data dir (store lock).
        assertEquals(1, local.signOuts)
        assertEquals(SERVER_URL, store.get())
    }

    @Test
    fun not_admitted_on_create_names_the_hub_and_another_hub_can_be_picked() = runTest(dispatcher) {
        val refusing = "https://closed.example.eu"
        val vm = OnboardingViewModel(
            manager(FakeGimClientFactory { config, _ ->
                FakeGimClient(backgroundScope, outboxDelays = null).also {
                    if (config.serverUrl == refusing) it.simulateNotAdmitted("")
                }
            }),
            FakeRecoveryKitCodec,
            refusing,
        )
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(OnboardingError.NotAdmitted(refusing), error)
            assertEquals(OnboardingStep.DISPLAY_NAME, step)
            assertFalse(isLoading)
        }
        assertEquals(refusing, store.get())

        vm.back()
        vm.onServerUrlChange(SERVER_URL)
        vm.createAccount()
        vm.submitDisplayName()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(OnboardingStep.RECOVERY_KIT, vm.state.value.step)
    }

    @Test
    fun network_error_while_connecting_maps_to_cannot_reach_server() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { _, _ -> throw GimException.Network("unreachable") }, FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
    }

    @Test
    fun invalid_input_from_connect_returns_to_welcome_with_server_url_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { _, _ -> throw GimException.InvalidInput("server_url: rejected") }, FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        assertEquals(FieldError.InvalidServerUrl, vm.state.value.serverUrlError)
        assertNull(vm.state.value.error)
        // The default was hidden; the refused address is shown for editing.
        assertTrue(vm.state.value.showServerField)
    }

    @Test
    fun invalid_input_from_create_maps_to_display_name_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingFactory(createIdentity = GimException.InvalidInput("display_name"))), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(FieldError.InvalidDisplayName, vm.state.value.displayNameError)
    }

    @Test
    fun retry_later_maps_to_try_again_later_on_create() = runTest(dispatcher) {
        val throttled = GimException.RetryLater(429, 30, "rate_limited")
        val create = OnboardingViewModel(manager(failingFactory(createIdentity = throttled)), FakeRecoveryKitCodec, SERVER_URL)
        create.finishIntro()
        create.createAccount()
        create.onDisplayNameChange("Sofia")
        create.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingError.TryAgainLater(30), create.state.value.error)

    }

    @Test
    fun other_create_errors_map_to_generic_and_editing_clears_them() = runTest(dispatcher) {
        val errors = listOf(
            GimException.Server(500, "boom"),
            GimException.Internal("bug"),
            GimException.NotImplemented(),
            GimException.Storage("keystore locked"),
        )
        for (error in errors) {
            val vm = OnboardingViewModel(manager(failingFactory(createIdentity = error)), FakeRecoveryKitCodec, SERVER_URL)
            vm.finishIntro()
            vm.createAccount()
            vm.onDisplayNameChange("Sofia")
            vm.submitDisplayName()
            advanceUntilIdle()
            assertEquals(error.toString(), OnboardingError.Generic, vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
            vm.onDisplayNameChange("Sofia C")
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun unverified_hub_asks_to_check_the_address() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(failingFactory(createIdentity = GimException.HubNotVerified("relay"))), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.createAccount()
        vm.onDisplayNameChange("Sofia")
        vm.submitDisplayName()
        advanceUntilIdle()
        assertEquals(OnboardingError.HubNotVerified, vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun actions_outside_their_step_are_ignored() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec, SERVER_URL)
        vm.finishIntro()
        vm.submitDisplayName()
        vm.continueFromKit()
        vm.onKitCheckChange("ABCD")
        vm.onRestoreKitTextChange("garbage")
        vm.events.test {
            vm.confirmKitCheck()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(OnboardingUiState.initial(SERVER_URL).copy(step = OnboardingStep.WELCOME), vm.state.value)
    }

    @Test
    fun wrong_kit_check_stays_until_the_text_changes() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec)
        vm.finishIntro()
        toRecoveryKit(vm)
        val code = vm.state.value.recoveryCode!!
        vm.continueFromKit()
        val wrong = if (code.endsWith("0000")) "1111" else "0000"
        vm.onKitCheckChange(wrong)
        assertTrue(vm.state.value.canConfirmKitCheck)
        vm.confirmKitCheck()
        assertEquals(OnboardingStep.RECOVERY_CHECK, vm.state.value.step)
        assertEquals(FieldError.KitCheckMismatch, vm.state.value.kitCheckError)
        assertEquals(code, vm.state.value.recoveryCode)

        vm.onKitCheckChange(wrong.take(3))
        assertNull(vm.state.value.kitCheckError)
    }

    @Test
    fun kit_text_and_kit_check_go_through_the_codec() = runTest(dispatcher) {
        val checked = mutableListOf<Pair<String, String>>()
        val codec = object : RecoveryKitCodec by FakeRecoveryKitCodec {
            override fun format(userId: String, recoveryCode: String, labels: RecoveryKitLabels) =
                "kit:$userId:$recoveryCode:${labels.title}"

            override fun matchesKeyEnding(recoveryCode: String, typed: String): Boolean {
                checked += recoveryCode to typed
                return typed == "ZZZZ"
            }
        }
        val vm = OnboardingViewModel(manager(fakeFactory()), codec)
        vm.finishIntro()
        toRecoveryKit(vm)
        val code = vm.state.value.recoveryCode!!
        assertEquals("kit:u1:$code:Kit", vm.formatRecoveryKit("u1", code, RecoveryKitLabels("Kit", "", "", "")))

        vm.continueFromKit()
        vm.onKitCheckChange("ZZZZ")
        vm.events.test {
            vm.confirmKitCheck()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertEquals(listOf(code to "ZZZZ"), checked)
    }

    @Test
    fun kit_check_needs_exactly_four_symbols() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec)
        vm.finishIntro()
        toRecoveryKit(vm)
        vm.continueFromKit()
        val cases = mapOf("" to false, "AB" to false, "ABC" to false, "AB-C D" to true, "abcd" to true, "ABCDE" to false)
        for ((input, expected) in cases) {
            vm.onKitCheckChange(input)
            assertEquals("'$input'", input, vm.state.value.kitCheck)
            assertEquals("'$input'", expected, vm.state.value.canConfirmKitCheck)
        }
        // Not confirmed with too few symbols.
        vm.onKitCheckChange("ABC")
        vm.confirmKitCheck()
        assertNull(vm.state.value.kitCheckError)
        assertEquals(OnboardingStep.RECOVERY_CHECK, vm.state.value.step)
    }

    @Test
    fun back_from_the_kit_check_shows_the_kit_again() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(fakeFactory()), FakeRecoveryKitCodec)
        vm.finishIntro()
        toRecoveryKit(vm)
        val kit = vm.state.value
        vm.continueFromKit()
        vm.onKitCheckChange("0000")
        vm.confirmKitCheck()
        vm.back()
        with(vm.state.value) {
            assertEquals(OnboardingStep.RECOVERY_KIT, step)
            assertEquals(kit.recoveryCode, recoveryCode)
            assertEquals(kit.accountId, accountId)
            assertEquals("", kitCheck)
            assertNull(kitCheckError)
        }
    }

    private companion object {
        const val SERVER_URL = "https://gim.example.eu"
        const val EMULATOR_URL = "http://10.0.2.2:8080"
        const val UNREACHABLE_URL = "http://10.0.2.2:1"
    }
}
