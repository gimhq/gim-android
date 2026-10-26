package im.g.apps.navigation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.AppContainer
import im.g.apps.BuildConfig
import im.g.apps.DemoAccountClient
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.settings.ThemeMode
import im.g.apps.push.PushChannel
import im.g.apps.testContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GimNavHostTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(Job())

    private lateinit var nav: NavHostController

    private fun launch(container: AppContainer) {
        val start = runBlocking { resolveStartDestination(container.manager) }
        rule.setContent {
            nav = rememberNavController()
            GimTheme { GimNavHost(container, start, navController = nav) }
        }
    }

    /** Skips the onboarding intro and waits for Welcome. */
    private fun skipIntro() {
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_intro_skip"), TIMEOUT)
        rule.onNodeWithTag("onboarding_intro_skip").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_create_account"), TIMEOUT)
    }

    /** Welcome prefills the build's hub and hides the field; shows it so the test can type the demo server. */
    private fun useAnotherServer() {
        rule.onNodeWithTag("onboarding_use_another_server").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_server_url"), TIMEOUT)
    }

    /** Runs the new-account onboarding from Welcome; claims "sofia" or skips the handle step. */
    private fun register(claimHandle: Boolean) {
        rule.onNodeWithTag("onboarding_create_account").assertExists()
        useAnotherServer()
        rule.onNodeWithTag("onboarding_server_url").performTextReplacement(DemoData.SERVER_URL)
        rule.onNodeWithTag("onboarding_create_account").performClick()
        rule.onNodeWithTag("onboarding_display_name").performTextInput("Sofia Costa")
        rule.onNodeWithTag("onboarding_submit").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_recovery_code"), TIMEOUT)
        val code = FakeGimClient.fakeRecoveryCode(1)
        rule.onNodeWithText(code).assertExists()
        rule.onNodeWithTag("onboarding_code_continue").performScrollTo().performClick()
        rule.onNodeWithTag("onboarding_kit_check").performTextInput(code.replace("-", "").takeLast(4))
        rule.onNodeWithTag("onboarding_kit_check_confirm").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_handle"), TIMEOUT)
        if (claimHandle) {
            rule.onNodeWithTag("onboarding_handle").performTextInput("sofia")
            rule.onNodeWithTag("onboarding_claim_handle").performClick()
        } else {
            rule.onNodeWithTag("onboarding_skip_handle").performClick()
        }
    }

    /** From the chat list: own avatar -> Settings -> the row labelled [row]. */
    private fun openSettingsRow(row: String) {
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithTag("chatlist_settings").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Settings"), TIMEOUT)
        rule.onNode(hasText(row) and hasClickAction()).performScrollTo().performClick()
    }

    @Test
    fun demo_chat_list_opens_a_chat_with_the_composer() {
        launch(testContainer(scope = scope))

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithText(DemoData.lukas.displayName).performClick()

        rule.waitUntilAtLeastOneExists(hasText("See you then!"), TIMEOUT)
        rule.onAllNodesWithText("Message")[0].assertExists()
    }

    @Test
    fun long_press_archives_a_chat_into_the_archived_section_and_back() {
        launch(testContainer(scope = scope))
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)

        rule.onNode(hasText(DemoData.lukas.displayName) and hasClickAction()).performTouchInput { longClick() }
        rule.onNodeWithText("Archive").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Archived (1)"), TIMEOUT)
        rule.onNodeWithText(DemoData.lukas.displayName).assertDoesNotExist()

        rule.onNodeWithTag("chatlist_archived").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Archived chats"), TIMEOUT)
        rule.onNode(hasText(DemoData.lukas.displayName) and hasClickAction()).performTouchInput { longClick() }
        rule.onNodeWithText("Unarchive").performClick()
        rule.waitUntilAtLeastOneExists(hasText("No archived chats"), TIMEOUT)

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntilAtLeastOneExists(hasText(DemoData.lukas.displayName), TIMEOUT)
        rule.onNodeWithTag("chatlist_archived").assertDoesNotExist()
    }

    /** Opens the chat info of the demo direct chat and scrolls to its chat actions. */
    private fun openDirectChatInfo() {
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.runOnIdle { nav.navigate(Routes.groupInfo(DemoData.DIRECT_CHAT_ID)) }
        rule.waitUntilAtLeastOneExists(hasText("Contact info"), TIMEOUT)
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("chat_action_block"))
    }

    @Test
    fun chat_info_hides_report_without_a_keep_server() {
        launch(testContainer(scope = scope))
        openDirectChatInfo()

        rule.onNodeWithTag("chat_action_block").assertExists()
        rule.onNodeWithTag("chat_action_report").assertDoesNotExist()
    }

    @Test
    fun chat_info_report_opens_the_report_and_sends_it_with_a_keep_server() {
        launch(testContainer(scope = scope, keepServerUrl = "https://keep.example.eu"))
        openDirectChatInfo()

        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("chat_action_report"))
        rule.onNodeWithTag("chat_action_report").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Report Lukas Berger"), TIMEOUT)
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("report_reason_spam"))
        rule.onNodeWithTag("report_reason_spam").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("report_send"))
        rule.onNodeWithTag("report_send").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("report_done"), TIMEOUT)
        rule.onNodeWithTag("report_done").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Contact info"), TIMEOUT)
    }

    @Test
    fun unregistered_start_runs_onboarding_then_shows_the_chat_list() {
        val demo = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope, outboxDelays = null) }
        launch(
            testContainer(
                storedUrl = null,
                scope = scope,
                factory = GimClientFactory { config, hooks -> DemoAccountClient(demo.create(config, hooks), registered = false) },
            ),
        )

        skipIntro()
        register(claimHandle = true)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithTag("onboarding_handle").assertDoesNotExist()
    }

    @Test
    fun sign_out_returns_to_onboarding_with_an_empty_back_stack_and_allows_registering_again() {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val demo = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope, outboxDelays = null) }
        val container = AppContainer(
            context = ApplicationProvider.getApplicationContext(),
            appScope = scope,
            serverUrlStore = store,
            clientFactory = GimClientFactory { config, hooks ->
                DemoAccountClient(demo.create(config, hooks), registered = store.get() != null)
            },
            recoveryKitCodec = FakeRecoveryKitCodec,
        )
        launch(container)

        openSettingsRow("Sign out")
        rule.onNodeWithText("Sign out?").assertExists()
        rule.onNodeWithTag("settings_sign_out_confirm").performClick()

        skipIntro()
        rule.runOnIdle {
            assertEquals(Routes.ONBOARDING, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
        assertNull(store.get())
        assertNull(container.manager.client.value)

        register(claimHandle = false)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    /** A container restoring the demo account, which registers again after the stored URL is forgotten. */
    private fun demoAccountContainer(store: InMemoryServerUrlStore, unreachableHubsOnDelete: List<String> = emptyList()) =
        AppContainer(
            context = ApplicationProvider.getApplicationContext(),
            appScope = scope,
            serverUrlStore = store,
            clientFactory = GimClientFactory { _, _ ->
                val demo = FakeGimClient.withDemoData(scope, outboxDelays = null)
                demo.unreachableHubsOnDelete = unreachableHubsOnDelete
                DemoAccountClient(demo, registered = store.get() != null)
            },
            recoveryKitCodec = FakeRecoveryKitCodec,
        )

    /** Settings -> "Delete account" -> tick the box -> delete. */
    private fun deleteAccount() {
        openSettingsRow("Delete account")
        rule.waitUntilAtLeastOneExists(hasTestTag("delete_account_understood"), TIMEOUT)
        rule.onNodeWithTag("delete_account_understood").performScrollTo().performClick()
        rule.onNodeWithTag("delete_account_confirm").performScrollTo().performClick()
    }

    @Test
    fun delete_account_returns_to_onboarding_with_a_fresh_client_and_allows_registering_again() {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val container = demoAccountContainer(store)
        launch(container)
        val deleted = container.manager.client.value!!

        deleteAccount()

        skipIntro()
        rule.runOnIdle {
            assertEquals(Routes.ONBOARDING, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
        assertNull(store.get())
        assertNull(container.manager.client.value)
        assertNull(runBlocking { deleted.session() })

        register(claimHandle = false)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test
    fun delete_account_names_unreachable_servers_before_onboarding() {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val container = demoAccountContainer(store, unreachableHubsOnDelete = listOf("hub-gone"))
        launch(container)

        deleteAccount()

        rule.waitUntilAtLeastOneExists(hasText("Your account is deleted"), TIMEOUT)
        rule.onNodeWithText("1 server couldn't be reached", substring = true).assertExists()
        rule.onNodeWithText("OK").performClick()

        skipIntro()
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
        assertNull(container.manager.client.value)
    }

    @Test
    fun new_recovery_key_shows_the_kit_checks_it_and_returns_to_settings() {
        val container = testContainer(scope = scope)
        launch(container)

        openSettingsRow("Create a new recovery key")
        rule.onNodeWithTag("new_key_create").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("new_key_recovery_key"), TIMEOUT)
        val code = rule.onNodeWithTag("new_key_recovery_key").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("")
        rule.onNodeWithText(container.manager.client.value!!.userId()!!).assertExists()
        rule.onNodeWithTag("new_key_saved").performScrollTo().performClick()
        rule.onNodeWithTag("new_key_check").performTextInput(FakeRecoveryKitCodec.keyEnding(code))
        rule.onNodeWithTag("new_key_check_confirm").performClick()
        rule.onNodeWithTag("new_key_done").performScrollTo().performClick()

        rule.waitUntilAtLeastOneExists(hasText("Settings"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
    }

    @Test
    fun leaving_the_new_key_before_the_check_asks_first() {
        launch(testContainer(scope = scope))

        openSettingsRow("Create a new recovery key")
        rule.onNodeWithTag("new_key_create").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("new_key_recovery_key"), TIMEOUT)
        rule.onNodeWithContentDescription("Go back").performClick()
        rule.onNodeWithText("Leave without checking?").assertExists()
        rule.onNodeWithText("Leave").performClick()

        rule.waitUntilAtLeastOneExists(hasText("Settings"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
    }

    @Test
    fun settings_opens_linked_devices_and_back_returns_to_settings() {
        launch(testContainer(scope = scope))

        openSettingsRow("Linked devices")

        rule.waitUntilAtLeastOneExists(hasText("This device"), TIMEOUT)
        rule.onNodeWithText("Your account ID").assertExists()
        rule.runOnIdle { assertEquals(Routes.DEVICES, nav.currentDestination?.route) }
        rule.onNodeWithContentDescription("Go back").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Linked devices"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
        rule.onNodeWithContentDescription("Go back").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
    }

    @Test
    fun settings_opens_servers() {
        launch(testContainer(scope = scope))

        openSettingsRow("Servers")

        rule.runOnIdle { assertEquals(Routes.HUBS, nav.currentDestination?.route) }
        rule.onNodeWithText("Servers").assertExists()
    }

    @Test
    fun settings_header_opens_the_profile_with_the_account_id() {
        val container = testContainer(scope = scope)
        launch(container)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithTag("chatlist_settings").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settings_header"), TIMEOUT)
        rule.onNodeWithTag("settings_header").performClick()

        val accountId = container.manager.client.value!!.userId()!!
        rule.waitUntilAtLeastOneExists(hasText(accountId), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_PROFILE, nav.currentDestination?.route) }
    }

    @Test
    fun settings_read_receipts_switch_saves_the_privacy_setting() {
        val container = testContainer(scope = scope)
        launch(container)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithTag("chatlist_settings").performClick()
        rule.onNodeWithTag("settings_read_receipts").performScrollTo().assertIsOn().performClick()

        rule.waitUntil(TIMEOUT) { runBlocking { !container.manager.client.value!!.privacy().readReceipts } }
        rule.onNodeWithTag("settings_read_receipts").assertIsOff()
    }

    @Test
    fun settings_opens_blocked_people_and_unblocks_by_name() {
        val container = testContainer(scope = scope)
        launch(container)
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        val client = container.manager.client.value!!
        runBlocking { client.blockUser(DemoData.lukas.userId) }

        openSettingsRow("Blocked people")

        rule.waitUntilAtLeastOneExists(hasText(DemoData.lukas.displayName), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_BLOCKED, nav.currentDestination?.route) }
        rule.onNodeWithTag("settings_blocked_unblock_${DemoData.lukas.userId}").performClick()
        rule.onNodeWithTag("settings_blocked_unblock_confirm").performClick()

        rule.waitUntilAtLeastOneExists(hasText("You haven't blocked anyone."), TIMEOUT)
        assertEquals(emptyList<String>(), runBlocking { client.privacy().blockedUserIds })
    }

    @Test
    fun settings_help_shows_versions_and_opens_the_licences() {
        launch(testContainer(scope = scope))

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithTag("chatlist_settings").performClick()
        rule.onNodeWithTag("settings_help").performScrollTo().performClick()

        rule.waitUntilAtLeastOneExists(hasText("How gim works"), TIMEOUT)
        rule.onNodeWithText(BuildConfig.VERSION_NAME).performScrollTo().assertExists()
        rule.onNodeWithText(BuildConfig.GIM_CORE_VERSION).performScrollTo().assertExists()
        rule.onNodeWithTag("help_licences").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("AndroidX and Jetpack Compose"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_LICENCES, nav.currentDestination?.route) }
    }

    @Test
    fun settings_opens_your_recovery_key() {
        launch(testContainer(scope = scope))

        openSettingsRow("Your recovery key")

        rule.waitUntilAtLeastOneExists(hasText("Your recovery key is a spare key"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_RECOVERY_KEY, nav.currentDestination?.route) }
    }

    @Test
    fun settings_opens_notifications_with_a_row_per_push_channel_that_exists() {
        launch(testContainer(scope = scope))

        openSettingsRow("Notifications")

        rule.waitUntilAtLeastOneExists(hasText("All notifications"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_NOTIFICATIONS, nav.currentDestination?.route) }
        rule.onNodeWithText("Messages").assertExists()
        rule.onNodeWithText("Chat invitations").assertExists()
        // A channel deep link only works for a channel the app created.
        val manager = NotificationManagerCompat.from(ApplicationProvider.getApplicationContext())
        PushChannel.entries.forEach { assertNotNull(manager.getNotificationChannel(it.id)) }
    }

    @Test
    fun settings_opens_appearance_and_choosing_dark_saves_it() {
        val container = testContainer(scope = scope)
        launch(container)

        openSettingsRow("Appearance")

        rule.waitUntilAtLeastOneExists(hasTestTag("appearance_dark"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS_APPEARANCE, nav.currentDestination?.route) }
        rule.onNodeWithTag("appearance_dark").performClick()
        rule.runOnIdle { assertEquals(ThemeMode.DARK, container.themeModeStore.mode.value) }
    }

    @Test
    fun settings_opens_language_and_the_back_stack_survives_recreation() {
        val container = testContainer(scope = scope)
        val start = runBlocking { resolveStartDestination(container.manager) }
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            nav = rememberNavController()
            GimTheme { GimNavHost(container, start, navController = nav) }
        }

        openSettingsRow("Language")
        rule.waitUntilAtLeastOneExists(hasTestTag("language_phone"), TIMEOUT)
        rule.onNodeWithTag("language_en").performClick()
        assertEquals("en", container.appLocales.current())
        // Applying a language recreates the activity; the saved back stack must keep the user here.
        restoration.emulateSavedInstanceStateRestore()

        rule.runOnIdle {
            assertEquals(Routes.SETTINGS_LANGUAGE, nav.currentDestination?.route)
            assertEquals(Routes.SETTINGS, nav.previousBackStackEntry?.destination?.route)
        }
        rule.onNodeWithTag("language_en").assertIsSelected()
    }

    @Test
    fun device_revoked_elsewhere_signs_out_to_onboarding() {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val container = AppContainer(
            context = ApplicationProvider.getApplicationContext(),
            appScope = scope,
            serverUrlStore = store,
            clientFactory = GimClientFactory { _, _ ->
                val demo = FakeGimClient.withDemoData(scope, outboxDelays = null)
                object : GimClient by demo {
                    override suspend fun devices(): List<DeviceInfo> = throw GimException.NotRegistered()
                }
            },
        )
        launch(container)

        openSettingsRow("Linked devices")
        rule.waitUntilAtLeastOneExists(hasText("This device was removed"), TIMEOUT)
        rule.onNodeWithText("OK").performClick()

        skipIntro()
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
        assertNull(store.get())
        assertNull(container.manager.client.value)
    }

    @Test
    fun link_to_existing_device_from_onboarding_shows_the_sas_then_the_chat_list() {
        launch(
            testContainer(
                storedUrl = null,
                scope = scope,
                factory = FakeGimClientFactory { _, _ -> FakeGimClient(scope, outboxDelays = null) },
            ),
        )

        skipIntro()
        useAnotherServer()
        rule.onNodeWithTag("onboarding_server_url").performTextReplacement(DemoData.SERVER_URL)
        rule.onNodeWithTag("onboarding_link_device").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("onboarding_link_sas"), TIMEOUT)
        rule.onNodeWithTag("onboarding_link_confirm").performClick()

        rule.waitUntilAtLeastOneExists(hasText("No chats yet"), TIMEOUT)
        rule.runOnIdle {
            assertEquals(Routes.CHAT_LIST, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test
    fun settings_opens_link_a_new_device_and_approves_a_pasted_code() {
        launch(testContainer(scope = scope))
        val code = FakeGimClient(scope, outboxDelays = null).startLink(emptyList()).qrString()

        openSettingsRow("Link a new device")

        rule.waitUntilAtLeastOneExists(hasText("Allow camera"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.LINK_DEVICE, nav.currentDestination?.route) }
        rule.onNode(hasSetTextAction()).performTextInput(code)
        rule.onNodeWithText("Continue").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Compare the numbers"), TIMEOUT)
        rule.onNodeWithText("The numbers match").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Done"), TIMEOUT)
        rule.onNodeWithText("Done").performClick()

        rule.waitUntilAtLeastOneExists(hasText("Linked devices"), TIMEOUT)
        rule.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
