package im.g.apps.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
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
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.feature.onboarding.QrPayloadKey
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Device link against a live gim-hub (instrumentation argument `gimServerUrl`): the app is the new device and links
 * through the real onboarding UI; the existing device is a second gim-core client of the same user in this process,
 * which approves the QR string read from the app's QR code semantics.
 */
@RunWith(AndroidJUnit4::class)
class LinkDeviceE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)

    // The test runs in the app process, which cannot write to the test APK's own data dir.
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-link-peer-$suffix")
    private var peerClient: GimClient? = null

    @After
    fun tearDown() {
        runBlocking {
            try {
                peerClient?.signOut()
            } catch (_: GimException) {
                // Local wipe below still runs.
            }
            // Leave the app signed out for the next test, which starts at onboarding.
            (compose.activity.application as GimApplication).container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun newDeviceLinksToExistingDevice() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // Existing device: a new identity on a second client.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        runBlocking {
            peer.createIdentity("Peer $suffix")
            peer.startSync()
        }
        val userId = peer.userId()

        // New device: Welcome -> Link to existing device -> QR code.
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("link to existing device", TAG_LINK_DEVICE)
        waitFor("link QR code shown", hasTestTag(TAG_LINK_QR))
        val qr = compose.onNodeWithTag(TAG_LINK_QR).fetchSemanticsNode().config.getOrNull(QrPayloadKey).orEmpty()
        assertTrue("QR payload is empty", qr.isNotEmpty())
        Log.i(LOG_TAG, "QR payload read (${qr.length} chars)")

        // Existing device approves; both show the same SAS.
        val approval = peerWait("peer approves the link") { peer.approveLink(qr) }
        val sas = approval.sas()
        assertTrue("SAS '$sas' is not six digits", SAS.matches(sas))
        val sasNode = hasTestTag(TAG_LINK_SAS)
        waitFor("app shows the SAS", sasNode)
        val appSas = compose.onNode(sasNode).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
        assertEquals(sas, appSas)

        // waitLink has returned on the app (the SAS is shown), so the old device may confirm now.
        peerWait("peer confirms the approval") { peer.confirmApproval(approval, true) }
        click("confirm SAS", TAG_LINK_CONFIRM)
        waitFor("chat list after linking", hasText(string(ChatListR.string.chatlist_title)))

        val appClient = (compose.activity.application as GimApplication).container.manager.client.value
        assertEquals(userId, appClient?.userId())
        val devices = peerWait("peer lists its devices") { peer.devices() }
        assertEquals("devices: $devices", 2, devices.count { !it.revoked })
        assertTrue(devices.any { it.deviceId == approval.deviceId() && !it.revoked })
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
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
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
        const val LOG_TAG = "LinkDeviceE2eTest"
        val SAS = Regex("\\d{3} \\d{3}")

        // Tags of internal constants in OnboardingTestTags.
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_LINK_DEVICE = "onboarding_link_device"
        const val TAG_LINK_QR = "onboarding_link_qr"
        const val TAG_LINK_SAS = "onboarding_link_sas"
        const val TAG_LINK_CONFIRM = "onboarding_link_confirm"
    }
}
