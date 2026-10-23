package im.g.apps.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
import im.g.apps.testing.RecoveryKit
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.skipIntro
import im.g.apps.testing.signOutFromSettings
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
 * Restore from the recovery kit against a live gim-hub (instrumentation argument `gimServerUrl`): the app creates an
 * account through onboarding and keeps the Account ID and recovery key its kit shows; a second gim-core client in
 * this process restores that user too and stands in for the lost device. The app signs out (its device stays listed
 * on the hub) and restores by pasting the kit, with "Remove all other devices" on, after which the hub rejects the
 * lost device (`NotRegistered`, gim-core docs/API.md).
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
    fun restoresFromRecoveryKitAndRevokesTheLostDevice() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // App: intro -> Welcome -> create account; the Account ID and recovery key of its kit are kept.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Lost $suffix")
        click("create account", TAG_CREATE_SUBMIT)
        val kit = compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        click("skip handle", TAG_SKIP_HANDLE)
        waitFor("chat list after creating", hasText(string(ChatListR.string.chatlist_title)))
        val userId = kit.accountId
        assertEquals(userId, appClient().userId())
        val firstDeviceId = peerWait("app lists its first device") { appClient().devices() }.single { it.isCurrent }.deviceId

        // The device that gets lost: a second device of the same user, added on a second client with the kit.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        peerWait("lost device restored") {
            while (true) {
                try {
                    peer.restoreFromRecovery(userId, kit.recoveryKey, revokeOthers = false)
                    break
                } catch (e: GimException.RetryLater) {
                    Log.i(LOG_TAG, "lost device restore throttled ($e); retrying")
                }
                delay(POLL_MS)
            }
        }
        val lostDeviceId = runBlocking { peer.devices() }.single { it.isCurrent }.deviceId

        // App: sign out (the device stays listed on the hub), then intro -> Welcome -> Restore -> paste the kit.
        compose.signOutFromSettings(LOG_TAG, timeoutMs, ::failStep)
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding after sign-out", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("restore with recovery kit", TAG_RESTORE, next = hasTestTag(TAG_KIT))
        waitFor("restore form shown", hasTestTag(TAG_KIT))
        compose.onNodeWithTag(TAG_KIT).performTextReplacement(kitText(kit))
        waitFor("Account ID read from the kit") { fieldText(TAG_USER_ID) == userId }
        compose.onNodeWithTag(TAG_REVOKE_OTHERS).performScrollTo().assertIsOn()
        waitFor("restore enabled", hasTestTag(TAG_SUBMIT) and isEnabled())
        click("restore account", TAG_SUBMIT)
        waitFor("chat list after restoring", hasText(string(ChatListR.string.chatlist_title)))

        val appClient = appClient()
        assertEquals(userId, appClient.userId())
        val devices = peerWait("app lists its devices") { appClient.devices() }
        val current = devices.single { it.isCurrent }
        assertNotEquals(lostDeviceId, current.deviceId)
        assertNotEquals(firstDeviceId, current.deviceId)
        assertTrue("devices: $devices", devices.single { it.deviceId == lostDeviceId }.revoked)
        assertTrue("devices: $devices", devices.single { it.deviceId == firstDeviceId }.revoked)
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

    /** The kit as the shared file holds it: title, labelled Account ID and recovery key, then the note. */
    private fun kitText(kit: RecoveryKit): String = listOf(
        string(OnboardingR.string.onboarding_kit_file_title),
        "${string(OnboardingR.string.onboarding_kit_account_id_label)}: ${kit.accountId}",
        "${string(OnboardingR.string.onboarding_kit_recovery_key_label)}: ${kit.recoveryKey}",
        "",
        string(OnboardingR.string.onboarding_kit_file_note),
    ).joinToString("\n")

    /** The app's own gim-core client (the test runs in the app process). */
    private fun appClient(): GimClient =
        checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)

    /** Runs a peer-side call bounded by [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> peerWait(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) =
        waitFor(step) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun fieldText(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun enterText(field: String, tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        node.performTextReplacement(value)
        fun actual() = fieldText(tag)
        try {
            compose.waitUntil(timeoutMs) { actual() == value }
        } catch (e: ComposeTimeoutException) {
            failStep("$field field holds '${actual()}' instead of '$value'", e)
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
        const val POLL_MS = 500L
        const val LOG_TAG = "RestoreE2eTest"

        // Tags of internal constants in OnboardingTestTags.
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_CREATE_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_RESTORE = "onboarding_restore"
        const val TAG_KIT = "onboarding_restore_kit"
        const val TAG_USER_ID = "onboarding_restore_user_id"
        const val TAG_REVOKE_OTHERS = "onboarding_restore_revoke_others"
        const val TAG_SUBMIT = "onboarding_restore_submit"
    }
}
