package im.g.apps.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
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
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.dismissSystemDialogs
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Restore from the recovery code against a live gim-hub (instrumentation argument `gimServerUrl`): a second gim-core
 * client in this process creates an identity and stands in for the lost device; the app restores that user through
 * the real onboarding UI with "Sign out all other devices" on, after which the hub rejects the lost device
 * (`NotRegistered`, gim-core docs/API.md).
 */
@RunWith(AndroidJUnit4::class)
class RestoreE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)

    // The test runs in the app process, which cannot write to the test APK's own data dir.
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-restore-peer-$suffix")
    private var peerClient: GimClient? = null

    @After
    fun tearDown() {
        runBlocking {
            try {
                peerClient?.signOut()
            } catch (_: GimException) {
                // The revoked peer may refuse; its directory is deleted below.
            }
            // Leave the app signed out for the next test, which starts at onboarding.
            (instrumentation.targetContext.applicationContext as GimApplication).container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun restoresFromRecoveryCodeAndRevokesTheLostDevice() {
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // The device that gets lost: a new identity on a second client; its recovery code is kept.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        val code = runBlocking { peer.createIdentity("Lost $suffix") }
        val userId = checkNotNull(peer.userId())
        val lostDeviceId = runBlocking { peer.devices() }.single { it.isCurrent }.deviceId

        // App: Welcome -> Restore with recovery code -> form.
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("restore with recovery code", TAG_RESTORE)
        waitFor("restore form shown", hasTestTag(TAG_USER_ID))
        enterText("user id", TAG_USER_ID, userId)
        // Typed the way people copy it from paper: lowercase, spaces instead of dashes.
        compose.onNodeWithTag(TAG_CODE).performTextReplacement(code.lowercase().replace('-', ' '))
        compose.onNodeWithTag(TAG_REVOKE_OTHERS).performScrollTo().assertIsOn()
        waitFor("restore enabled", hasTestTag(TAG_SUBMIT) and isEnabled())
        click("restore account", TAG_SUBMIT)
        waitFor("chat list after restoring", hasText(string(ChatListR.string.chatlist_title)))

        val appClient = checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)
        assertEquals(userId, appClient.userId())
        val devices = peerWait("app lists its devices") { appClient.devices() }
        val current = devices.single { it.isCurrent }
        assertNotEquals(lostDeviceId, current.deviceId)
        assertTrue("devices: $devices", devices.single { it.deviceId == lostDeviceId }.revoked)
        assertEquals("devices: $devices", listOf(current.deviceId), devices.filterNot { it.revoked }.map { it.deviceId })

        // The lost device can no longer sign in: its calls fail with NotRegistered.
        peerWait("lost device rejected with NotRegistered") {
            while (true) {
                try {
                    peer.findUser("nobody_$suffix")
                } catch (_: GimException.NotRegistered) {
                    break
                } catch (e: GimException) {
                    Log.i(LOG_TAG, "lost device call failed with $e; retrying")
                }
                delay(POLL_MS)
            }
        }
    }

    /** Runs a peer-side call bounded by [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> peerWait(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
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

    /** Peer [PlatformHooks]: secrets in memory, data in a per-run cache dir. */
    private class InMemoryHooks(private val dir: File) : PlatformHooks {
        private val secrets = ConcurrentHashMap<String, ByteArray>()

        override fun secureStoreGet(key: String): ByteArray? = secrets[key]

        override fun secureStoreSet(key: String, value: ByteArray) {
            secrets[key] = value
        }

        override fun secureStoreDelete(key: String) {
            secrets.remove(key)
        }

        override fun dataDir(): String = dir.apply { mkdirs() }.absolutePath

        override fun isNetworkReachable(): Boolean = true
    }

    private companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L
        const val LOG_TAG = "RestoreE2eTest"

        // Tags of internal constants in OnboardingTestTags.
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_RESTORE = "onboarding_restore"
        const val TAG_USER_ID = "onboarding_restore_user_id"
        const val TAG_CODE = "onboarding_restore_code"
        const val TAG_REVOKE_OTHERS = "onboarding_restore_revoke_others"
        const val TAG_SUBMIT = "onboarding_restore_submit"
    }
}
