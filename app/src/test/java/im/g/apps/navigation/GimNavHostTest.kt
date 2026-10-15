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
import im.g.apps.core.bridge.GimClientFactory
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

    private fun register() {
        rule.onNodeWithText("Create your account").assertExists()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(DemoData.SERVER_URL)
        fields[1].performTextInput("sofia")
        fields[2].performTextInput("Sofia Costa")
        rule.onNodeWithText("Create account").performClick()
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

        register()

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithText("Create your account").assertDoesNotExist()
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

        rule.waitUntilAtLeastOneExists(hasText("Create your account"), TIMEOUT)
        rule.runOnIdle {
            assertEquals(Routes.ONBOARDING, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
        assertNull(store.get())
        assertNull(container.manager.client.value)

        register()

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
