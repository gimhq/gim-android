package im.g.apps.e2e

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
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
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.skipIntro
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.devices.R as DevicesR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Device management against a live gim-hub (instrumentation argument `gimServerUrl`): the app creates an identity,
 * an in-process gim-core peer joins the same user through the link handshake (driven through the bridge on both
 * sides), the app lists both devices and revokes the peer from the Devices screen.
 */
@RunWith(AndroidJUnit4::class)
class DevicesE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-devices-peer-$suffix")
    private var peerClient: GimClient? = null

    @After
    fun tearDown() {
        runBlocking {
            try {
                peerClient?.signOut()
            } catch (_: GimException) {
                // The revoked peer may refuse; its directory is deleted below.
            }
            // Leave the app signed out for the next test class.
            (instrumentation.targetContext.applicationContext as GimApplication).container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun listsLinkedDeviceAndRevokesIt() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // Onboarding: skip the intro, create identity, pass the recovery kit check, skip the handle -> chat list.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Devices $suffix")
        click("create account", TAG_SUBMIT)
        compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitFor("handle step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip handle", TAG_SKIP_HANDLE)
        waitFor("chat list", hasText(string(ChatListR.string.chatlist_title)))

        // Link handshake through the bridge: peer (new device) and the app's client (old device).
        val app = (compose.activity.application as GimApplication).container.manager.client.value
            ?: throw AssertionError("app has no client after onboarding")
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        val peerDeviceId = bounded("link handshake") {
            val link = peer.startLink(listOf(serverUrl))
            val approval = app.approveLink(link.qrString())
            val sas = peer.waitLink(link)
            assertEquals("SAS differs between the devices", approval.sas(), sas)
            peer.confirmLink(true)
            app.confirmApproval(approval, true)
            peer.startSync()
            approval.deviceId()
        }
        Log.i(LOG_TAG, "peer linked")

        // Devices screen lists both devices.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_more_options)).performClick()
        waitFor("overflow menu", hasText(string(ChatListR.string.chatlist_devices)))
        compose.onNodeWithText(string(ChatListR.string.chatlist_devices)).performClick()
        val peerRow = hasTestTag(TAG_ROW_PREFIX + peerDeviceId)
        waitFor("peer device listed", peerRow)
        waitFor("this device listed", hasText(string(DevicesR.string.devices_this_device)))
        val rows = SemanticsMatcher("device row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TAG_ROW_PREFIX) == true
        }
        assertEquals(2, compose.onAllNodes(rows).fetchSemanticsNodes().size)
        compose.onNodeWithTag(TAG_USER_ID).assertIsDisplayed()

        // Revoke the peer through the confirmation dialog.
        compose.onNodeWithContentDescription(string(DevicesR.string.devices_revoke)).performClick()
        waitFor("revoke dialog", hasText(string(DevicesR.string.devices_revoke_title)))
        compose.onNode(hasText(string(DevicesR.string.devices_revoke_confirm)) and hasClickAction()).performClick()
        waitFor("peer shown revoked", hasText(string(DevicesR.string.devices_revoked)) and hasAnyAncestor(peerRow))
        waitFor("revoke action gone") {
            compose.onAllNodes(hasContentDescription(string(DevicesR.string.devices_revoke)))
                .fetchSemanticsNodes().isEmpty()
        }

        // The hub refuses the revoked peer; gim-core then drops its session and its calls fail with NotRegistered.
        bounded("peer reports NotRegistered") {
            while (true) {
                try {
                    peer.devices()
                } catch (_: GimException.NotRegistered) {
                    break
                }
                delay(POLL_MS)
            }
        }
    }

    /** Runs [block] bounded by twice [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> bounded(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs * 2) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
    }

    private fun string(@StringRes id: Int): String = compose.activity.getString(id)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) =
        waitFor(step) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** Replaces the text of the field tagged [tag] and waits until the field holds exactly [value]. */
    private fun enterText(field: String, tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        node.performTextReplacement(value)
        waitFor("$field field holds '$value'") {
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == value
        }
    }

    /** Clicks the enabled, displayed button tagged [tag] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, tag: String, next: SemanticsMatcher? = null) =
        compose.clickWhenSettled(LOG_TAG, button, tag, timeoutMs, next)

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    /** Fails with [step] after dumping the semantics tree to logcat under [LOG_TAG]. */
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
        const val POLL_MS = 500L
        const val LOG_TAG = "DevicesE2eTest"

        // Tags of internal constants in the feature modules (OnboardingTestTags, DevicesTestTags).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_USER_ID = "devices_user_id"
        const val TAG_ROW_PREFIX = "devices_row_"
    }
}
