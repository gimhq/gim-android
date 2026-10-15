package im.g.apps.navigation

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.AppContainer
import im.g.apps.DemoAccountClient
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.testContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    /** Runs the new-account onboarding; claims "sofia" or skips the handle step. */
    private fun register(claimHandle: Boolean) {
        rule.onNodeWithText("Welcome to gim").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput(DemoData.SERVER_URL)
        rule.onNodeWithText("Create new account").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Sofia Costa")
        rule.onNodeWithText("Create account").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Save your recovery code"), TIMEOUT)
        rule.onNodeWithText(FakeGimClient.fakeRecoveryCode(1)).assertExists()
        rule.onNodeWithText("I have saved my recovery code").performClick()
        rule.onNodeWithText("Continue").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Choose a handle"), TIMEOUT)
        if (claimHandle) {
            rule.onNode(hasSetTextAction()).performTextInput("sofia")
            rule.onNodeWithText("Claim handle").performClick()
        } else {
            rule.onNodeWithText("Skip for now").performClick()
        }
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
    fun unregistered_start_runs_onboarding_then_shows_the_chat_list() {
        val demo = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope, outboxDelays = null) }
        launch(
            testContainer(
                storedUrl = null,
                scope = scope,
                factory = GimClientFactory { config, hooks -> DemoAccountClient(demo.create(config, hooks), registered = false) },
            ),
        )

        register(claimHandle = true)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithText("Choose a handle").assertDoesNotExist()
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
        )
        launch(container)

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Sign out").performClick()
        rule.onNode(hasText("Sign out") and hasClickAction()).performClick()

        rule.waitUntilAtLeastOneExists(hasText("Welcome to gim"), TIMEOUT)
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

    @Test
    fun chat_list_menu_opens_devices_and_back_returns() {
        launch(testContainer(scope = scope))

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Devices").performClick()

        rule.waitUntilAtLeastOneExists(hasText("This device"), TIMEOUT)
        rule.onNodeWithText("Your user ID").assertExists()
        rule.runOnIdle { assertEquals(Routes.DEVICES, nav.currentDestination?.route) }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
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

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Devices").performClick()
        rule.waitUntilAtLeastOneExists(hasText("This device was removed"), TIMEOUT)
        rule.onNodeWithText("OK").performClick()

        rule.waitUntilAtLeastOneExists(hasText("Welcome to gim"), TIMEOUT)
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
        assertNull(store.get())
        assertNull(container.manager.client.value)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
