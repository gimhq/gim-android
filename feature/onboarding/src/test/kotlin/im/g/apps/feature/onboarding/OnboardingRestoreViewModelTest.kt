package im.g.apps.feature.onboarding

import app.cash.turbine.test
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakePlatformHooks
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
class OnboardingRestoreViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = InMemoryServerUrlStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A fake client that knows [USER_ID] (with one earlier device) and records restore calls. */
    private class RestoreClient(val fake: FakeGimClient) : GimClient by fake {
        var restoreError: GimException? = null
        val calls = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun restoreFromRecovery(userId: String, recoveryCode: String, revokeOthers: Boolean) {
            calls += Triple(userId, recoveryCode, revokeOthers)
            restoreError?.let { throw it }
            fake.restoreFromRecovery(userId, recoveryCode, revokeOthers)
        }
    }

    private val clients = mutableListOf<RestoreClient>()

    private fun TestScope.manager(connectError: GimException? = null, configure: (RestoreClient) -> Unit = {}) =
        GimClientManager(
            GimClientFactory { _, _ ->
                connectError?.let { throw it }
                val fake = FakeGimClient(backgroundScope, outboxDelays = null).apply {
                    addRecoverableUser(UserProfile(USER_ID, "", "Sofia Costa"), RECOVERY_CODE, listOf(OLD_DEVICE))
                }
                RestoreClient(fake).also(configure).also { clients += it }
            },
            FakePlatformHooks(),
            store,
        )

    /**
     * Models gim-core's onboarding retry: a hub in [down] fails `restoreFromRecovery` with `Network`, and the call
     * discards what the attempt stored, so the same client can call it again. [signOuts] counts `signOut` calls,
     * which a retry must not need.
     */
    private class CoreLocalState(val down: MutableSet<String>) {
        val clients = mutableListOf<String>()
        var signOuts = 0
    }

    private fun TestScope.coreManager(local: CoreLocalState) = GimClientManager(
        GimClientFactory { config, _ ->
            val fake = FakeGimClient(backgroundScope, outboxDelays = null).apply {
                addRecoverableUser(UserProfile(USER_ID, "", "Sofia Costa"), RECOVERY_CODE, listOf(OLD_DEVICE))
            }
            local.clients += config.serverUrl
            object : GimClient by fake {
                override suspend fun restoreFromRecovery(userId: String, recoveryCode: String, revokeOthers: Boolean) {
                    if (config.serverUrl in local.down) throw GimException.Network("connection refused")
                    fake.restoreFromRecovery(userId, recoveryCode, revokeOthers)
                }

                override suspend fun signOut() {
                    local.signOuts++
                    fake.signOut()
                }
            }
        },
        FakePlatformHooks(),
        store,
    )

    /** Welcome -> restore form filled with [userId] and [code]. */
    private fun form(vm: OnboardingViewModel, userId: String = USER_ID, code: String = RECOVERY_CODE) {
        vm.startRestore()
        assertEquals(OnboardingStep.RESTORE, vm.state.value.step)
        vm.onRestoreUserIdChange(userId)
        vm.onRestoreCodeChange(code)
    }

    @Test
    fun restore_is_reachable_from_welcome_only_with_a_valid_server_url() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager())
        vm.startRestore()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)

        vm.onServerUrlChange(SERVER_URL)
        vm.startRestore()
        val state = vm.state.value
        assertEquals(OnboardingStep.RESTORE, state.step)
        assertTrue(state.handlesBack)
        assertTrue(state.revokeOtherDevices)
        assertFalse(state.canRestore)

        vm.back()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun restore_with_pasted_values_revokes_others_and_finishes() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        // Pasted with whitespace, lowercase and spaces instead of dashes.
        form(vm, userId = "  ${USER_ID.uppercase()}\n", code = RECOVERY_CODE.lowercase().replace('-', ' '))
        assertEquals(USER_ID, vm.state.value.restoreUserId)
        assertEquals(RECOVERY_CODE.replace("-", ""), vm.state.value.restoreCode)
        assertTrue(vm.state.value.canRestore)

        vm.events.test {
            vm.restore()
            assertTrue(vm.state.value.isLoading)
            assertFalse(vm.state.value.canRestore)
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        val client = clients.single()
        assertEquals(listOf(Triple(USER_ID, RECOVERY_CODE, true)), client.calls)
        assertEquals(USER_ID, client.userId())
        assertTrue(client.devices().single { it.deviceId == OLD_DEVICE.deviceId }.revoked)
        assertFalse(vm.state.value.isLoading)
        assertEquals("", vm.state.value.restoreCode)
        assertEquals(SERVER_URL, store.get())
    }

    @Test
    fun restore_can_keep_other_devices() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        form(vm)
        vm.onRevokeOtherDevicesChange(false)
        vm.restore()
        advanceUntilIdle()

        val client = clients.single()
        assertEquals(false, client.calls.single().third)
        assertFalse(client.devices().single { it.deviceId == OLD_DEVICE.deviceId }.revoked)
    }

    @Test
    fun user_id_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        vm.startRestore()
        val cases = mapOf(
            "" to null,
            USER_ID to null,
            USER_ID.dropLast(1) to FieldError.InvalidUserId,
            USER_ID + "a" to FieldError.InvalidUserId,
            "d" + USER_ID.drop(1) to FieldError.InvalidUserId,
            USER_ID.dropLast(1) + "b" to FieldError.InvalidUserId, // non-zero trailing bits
            USER_ID.replaceRange(5, 6, "1") to FieldError.InvalidUserId, // not in the base32 alphabet
        )
        for ((input, expected) in cases) {
            vm.onRestoreUserIdChange(input)
            assertEquals("'$input'", expected, vm.state.value.restoreUserIdError)
        }
    }

    @Test
    fun recovery_code_validation_matrix() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        form(vm)
        val symbols = RECOVERY_CODE.replace("-", "")
        val cases = mapOf(
            "" to (null to false),
            "7BQ2-K9" to (null to false),
            RECOVERY_CODE to (null to true),
            RECOVERY_CODE.lowercase() to (null to true),
            // I/L -> 1, O -> 0, like gim-core's parser.
            symbols.replace('1', 'l').replace('0', 'o') to (null to true),
            symbols.chunked(5).joinToString(" ") to (null to true),
            symbols + "0" to (FieldError.InvalidRecoveryCode to false),
            symbols.dropLast(1) + "U" to (FieldError.InvalidRecoveryCode to false),
            symbols.dropLast(1) + "1" to (FieldError.InvalidRecoveryCode to false), // non-zero padding bits
            "7BQ2!" to (FieldError.InvalidRecoveryCode to false),
        )
        for ((input, expected) in cases) {
            vm.onRestoreCodeChange(input)
            assertEquals("'$input'", expected.first, vm.state.value.restoreCodeError)
            assertEquals("'$input'", expected.second, vm.state.value.canRestore)
        }
    }

    @Test
    fun unknown_user_maps_to_user_id_error_until_it_changes() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { it.restoreError = GimException.NotFound("device list") }, SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()

        assertEquals(FieldError.UnknownUser, vm.state.value.restoreUserIdError)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.canRestore)
        assertEquals(OnboardingStep.RESTORE, vm.state.value.step)

        vm.onRestoreUserIdChange(USER_ID)
        assertNull(vm.state.value.restoreUserIdError)
        assertTrue(vm.state.value.canRestore)
    }

    @Test
    fun unknown_user_from_the_fake_maps_to_user_id_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        form(vm, userId = OTHER_USER_ID)
        vm.restore()
        advanceUntilIdle()
        assertEquals(FieldError.UnknownUser, vm.state.value.restoreUserIdError)
    }

    @Test
    fun wrong_code_maps_to_code_error_and_a_retry_succeeds() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        form(vm, code = OTHER_CODE)
        vm.restore()
        advanceUntilIdle()

        assertEquals(FieldError.WrongRecoveryCode, vm.state.value.restoreCodeError)
        assertFalse(vm.state.value.canRestore)
        assertNull(clients.single().userId())

        vm.events.test {
            vm.onRestoreCodeChange(RECOVERY_CODE)
            vm.restore()
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertEquals(USER_ID, clients.single().userId())
    }

    @Test
    fun malformed_code_from_core_maps_to_invalid_code_error() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { it.restoreError = GimException.InvalidInput("recovery code") }, SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()
        assertEquals(FieldError.InvalidRecoveryCode, vm.state.value.restoreCodeError)
        assertNull(vm.state.value.error)
    }

    @Test
    fun retry_later_shows_the_wait_and_allows_a_retry() = runTest(dispatcher) {
        val vm = OnboardingViewModel(
            manager { it.restoreError = GimException.RetryLater(429, 42, "rate_limited") },
            SERVER_URL,
        )
        form(vm)
        vm.restore()
        advanceUntilIdle()

        assertEquals(OnboardingError.TryAgainLater(42), vm.state.value.error)
        assertTrue(vm.state.value.canRestore)

        clients.single().restoreError = null
        vm.events.test {
            vm.restore()
            assertNull(vm.state.value.error)
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
    }

    @Test
    fun network_error_maps_to_cannot_reach_server_and_editing_clears_it() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { it.restoreError = GimException.Network("refused") }, SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()

        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
        assertTrue(vm.state.value.canRestore)
        vm.onRestoreCodeChange(RECOVERY_CODE)
        assertNull(vm.state.value.error)
    }

    @Test
    fun retry_after_network_error_on_restore_finishes() = runTest(dispatcher) {
        val local = CoreLocalState(mutableSetOf(SERVER_URL))
        val vm = OnboardingViewModel(coreManager(local), SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)

        local.down.clear()
        vm.events.test {
            vm.restore()
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertNull(vm.state.value.restoreCodeError)
        // The same client retries: no sign-out, no second client.
        assertEquals(listOf(SERVER_URL), local.clients)
        assertEquals(0, local.signOuts)
    }

    @Test
    fun retry_with_another_server_url_after_network_error_on_restore_finishes() = runTest(dispatcher) {
        val local = CoreLocalState(mutableSetOf(UNREACHABLE_URL))
        val vm = OnboardingViewModel(coreManager(local), UNREACHABLE_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)

        vm.back()
        vm.onServerUrlChange(SERVER_URL)
        vm.startRestore()
        assertEquals(USER_ID, vm.state.value.restoreUserId)
        vm.events.test {
            vm.restore()
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertEquals(listOf(UNREACHABLE_URL, SERVER_URL), local.clients)
        // The client for the first URL is signed out before the next one opens the same data dir (store lock).
        assertEquals(1, local.signOuts)
        assertEquals(SERVER_URL, store.get())
    }

    @Test
    fun network_error_while_connecting_maps_to_cannot_reach_server() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(connectError = GimException.Network("dns")), SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()
        assertEquals(OnboardingError.CannotReachServer, vm.state.value.error)
        assertEquals(OnboardingStep.RESTORE, vm.state.value.step)
    }

    @Test
    fun suspended_user_maps_to_account_suspended_and_keeps_the_form() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { it.restoreError = GimException.UserSuspended("hub-a") }, SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(OnboardingError.AccountSuspended, state.error)
        assertEquals(OnboardingStep.RESTORE, state.step)
        assertEquals(USER_ID, state.restoreUserId)
        assertFalse(state.isLoading)
        assertEquals(1, clients.single().calls.size)
    }

    @Test
    fun other_errors_map_to_generic() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager { it.restoreError = GimException.Server(500, "boom") }, SERVER_URL)
        form(vm)
        vm.restore()
        advanceUntilIdle()
        assertEquals(OnboardingError.Generic, vm.state.value.error)
    }

    @Test
    fun double_submit_and_edits_while_loading_are_ignored() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        form(vm)
        vm.restore()
        vm.restore()
        vm.onRestoreUserIdChange("")
        vm.onRevokeOtherDevicesChange(false)
        vm.back()
        assertEquals(OnboardingStep.RESTORE, vm.state.value.step)
        advanceUntilIdle()
        assertEquals(listOf(Triple(USER_ID, RECOVERY_CODE, true)), clients.single().calls)
    }

    @Test
    fun restore_is_ignored_outside_its_step_or_with_an_incomplete_form() = runTest(dispatcher) {
        val vm = OnboardingViewModel(manager(), SERVER_URL)
        vm.onRestoreUserIdChange(USER_ID)
        vm.onRestoreCodeChange(RECOVERY_CODE)
        vm.restore()
        vm.startRestore()
        vm.onRestoreCodeChange("7BQ2")
        vm.restore()
        advanceUntilIdle()
        assertTrue(clients.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    private companion object {
        const val SERVER_URL = "https://gim.example.eu"
        const val UNREACHABLE_URL = "http://10.0.2.2:1"
        const val USER_ID = "uabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvq"
        const val OTHER_USER_ID = "u" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" + "a"
        const val RECOVERY_CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7R"
        const val OTHER_CODE = "0000-0000-0000-0000-0000-0000-0000-0000-0000-000"
        val OLD_DEVICE = DeviceInfo("d-old", 1_000, false, false)
    }
}
