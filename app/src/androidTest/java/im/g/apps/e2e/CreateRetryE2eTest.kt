package im.g.apps.e2e

import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Creating an account after the hub could not be reached (instrumentation argument `gimServerUrl` for the live
 * gim-hub): `createIdentity` against a closed port of the same host fails twice with "Cannot reach the hub" (the
 * failed attempt must not leave local state that turns the retry into a generic error), then the corrected address
 * reaches the recovery code.
 */
@RunWith(AndroidJUnit4::class)
class CreateRetryE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Same host, a port nothing listens on: connections are refused. */
    private val unreachableUrl = Uri.parse(serverUrl).let { "${it.scheme}://${it.host}:$CLOSED_PORT" }

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)

    @After
    fun tearDown() {
        // Leave the app signed out for the next test, which starts at onboarding.
        runBlocking { (instrumentation.targetContext.applicationContext as GimApplication).container.signOut() }
    }

    @Test
    fun createSucceedsAfterFailingAgainstAnUnreachableHub() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }
        val cannotReach = hasText(string(OnboardingR.string.onboarding_error_cannot_reach_server))

        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, unreachableUrl)
        click("create new account", TAG_CREATE_ACCOUNT)
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Retry $suffix")
        click("create account (unreachable hub)", TAG_SUBMIT)
        waitFor("cannot reach the hub", cannotReach)

        // Same address again: still the network error, not a leftover-state failure.
        click("create account again (unreachable hub)", TAG_SUBMIT)
        compose.waitForIdle()
        waitFor("cannot reach the hub on retry", cannotReach)
        compose.onAllNodes(hasText(string(OnboardingR.string.onboarding_error_generic))).assertCountEquals(0)

        // Corrected address: back to Welcome, fix it, submit the kept display name.
        click("back to welcome", TAG_BACK)
        waitFor("welcome shown", hasTestTag(TAG_CREATE_ACCOUNT))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT)
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        click("create account (real hub)", TAG_SUBMIT)
        waitFor("recovery code shown", hasTestTag(TAG_RECOVERY_CODE))

        val appClient = (compose.activity.application as GimApplication).container.manager.client.value
        assertNotNull("signed in after the retry", appClient?.userId())
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun waitFor(step: String, matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun enterText(field: String, tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        node.performTextReplacement(value)
        fun actual() = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        try {
            compose.waitUntil(timeoutMs) { actual() == value }
        } catch (e: ComposeTimeoutException) {
            failStep("$field field holds '${actual()}' instead of '$value'", e)
        }
    }

    /** Hides the soft keyboard, then clicks the enabled, displayed button tagged [tag]. */
    private fun click(button: String, tag: String) {
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        Log.i(LOG_TAG, "clicking $button")
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    /** Fails with [step], after dumping the semantics tree of every window to logcat under [LOG_TAG]. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString().lines().forEach { Log.d(LOG_TAG, it) }
        }
        throw AssertionError("step '$step' timed out after $timeoutMs ms", cause)
    }

    private companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val CLOSED_PORT = 1
        const val TIMEOUT_MS = 30_000L
        const val LOG_TAG = "CreateRetryE2eTest"

        // Tags of internal constants in OnboardingTestTags.
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_BACK = "onboarding_back"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_RECOVERY_CODE = "onboarding_recovery_code"
    }
}
