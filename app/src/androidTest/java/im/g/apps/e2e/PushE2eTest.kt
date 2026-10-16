package im.g.apps.e2e

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.flow.connectionStateFlow
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.push.MockPushProvider
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Push end to end against a live gim-hub whose push webhook points at gim-push-mock (instrumentation arguments
 * `gimServerUrl` and `gimPushMockUrl`; skipped without the mock URL): the app onboards and registers its
 * `mock-android` token and goes to the background (sync stops). An in-process gim-core peer then adds it to a group
 * and sends a text; the debug build's mock poller hands each push to gim-core, so the invitation and then the
 * decrypted text show up in the notification shade. Tapping the text's notification opens the chat.
 */
@RunWith(AndroidJUnit4::class)
class PushE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL
    private val mockUrl = args.getString("gimPushMockUrl")?.takeIf { it.isNotBlank() }

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-push-peer-$suffix")
    private val device = UiDevice.getInstance(instrumentation)
    private val container get() = (instrumentation.targetContext.applicationContext as GimApplication).container
    private val mock get() = container.pushProvider as MockPushProvider
    private var peerClient: GimClient? = null

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        runBlocking {
            try {
                peerClient?.signOut()
            } catch (_: GimException) {
                // Its directory is deleted below.
            }
            // Later classes on this install run without push, signed out.
            mock.setBaseUrl(null)
            container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun backgroundedAppShowsPushedMessageAndOpensChat() {
        assumeTrue("no gimPushMockUrl argument (gim-push-mock not running)", mockUrl != null)
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }
        val app = instrumentation.targetContext.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app, Manifest.permission.POST_NOTIFICATIONS)
        }
        Log.i(LOG_TAG, "hub $serverUrl, push mock $mockUrl")
        mock.setBaseUrl(mockUrl)

        // Onboarding: create identity, acknowledge the recovery code, skip the handle -> chat list.
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Push $suffix")
        click("create account", TAG_SUBMIT)
        waitFor("recovery code shown", hasTestTag(TAG_RECOVERY_CODE))
        compose.onNodeWithTag(TAG_CODE_SAVED).performScrollTo().performClick()
        click("recovery code continue", TAG_CODE_CONTINUE, next = hasTestTag(TAG_SKIP_HANDLE))
        waitFor("handle step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip handle", TAG_SKIP_HANDLE)
        waitFor("chat list", hasText(string(ChatListR.string.chatlist_title)))

        // Registered with the hub (mock-android token) and polling the mock from its newest delivery on.
        waitFor("push registered and polling") { container.push.active.value && mock.polling.value }
        Log.i(LOG_TAG, "push registered with token ${mock.token.take(6)}…")
        val appUserId = checkNotNull(container.manager.client.value?.userId()) { "app has no user id" }

        // HOME: the process stays, sync stops; only the push path can deliver now.
        device.pressHome()
        val appClient = checkNotNull(container.manager.client.value)
        try {
            runBlocking { withTimeout(timeoutMs) { appClient.connectionStateFlow().first { it == ConnectionState.OFFLINE } } }
            Log.i(LOG_TAG, "app sync stopped")
        } catch (_: TimeoutCancellationException) {
            Log.w(LOG_TAG, "app connection not OFFLINE after HOME; continuing")
        }

        // The peer opens a group with the backgrounded app user: gim-core joins it from the push -> Invite
        // notification titled with the group, "<inviter>: Added you to the chat" (the inviter may still be a handle
        // or user id until their profile is on the hub).
        val groupName = "Push E2E $suffix"
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        val chatId = bounded("peer creates the group") {
            peer.createIdentity("Peer $suffix")
            peer.startSync()
            peer.createGroupChat(groupName, listOf(appUserId))
        }
        Log.i(LOG_TAG, "peer created the group")

        device.openNotification()
        if (!device.wait(Until.hasObject(By.text(groupName)), timeoutMs * 2)) {
            failStep("invite notification titled with the group in the shade", AssertionError("not found"))
        }
        if (!device.wait(Until.hasObject(By.textContains(INVITE_BODY)), timeoutMs)) {
            failStep("invite notification text in the shade", AssertionError("not found"))
        }
        val inviteText = device.findObject(By.textContains(INVITE_BODY)).text
        Log.i(LOG_TAG, "invite notification shown: '$groupName' / '$inviteText'")
        check(inviteText.endsWith(": $INVITE_BODY")) { "invite text does not name the inviter: '$inviteText'" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            device.pressBack()
        }
        // With two notifications of the app, the MIUI shade bundles them and the tap below did not reach the text's
        // own notification (the chat never opened); keep only the text's.
        NotificationManagerCompat.from(instrumentation.targetContext).cancelAll()

        val text = "Mooring at Poros $suffix"
        bounded("peer sends the text") { peer.sendText(chatId, text, null) }
        Log.i(LOG_TAG, "peer sent the text")

        device.openNotification()
        if (!device.wait(Until.hasObject(By.textContains(text)), timeoutMs * 2)) {
            failStep("notification with the pushed text in the shade", AssertionError("not found"))
        }
        Log.i(LOG_TAG, "notification shown")

        // Tap -> the chat opens.
        device.findObject(By.textContains(text)).click()
        waitFor("chat opened from the notification", hasTestTag(TAG_COMPOSER))
        waitFor("chat shows the pushed text", hasText(text))
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

    /** Also waits while no compose hierarchy exists, e.g. while the activity is recreated from a notification. */
    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitFor(step) {
        try {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }
    }

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

    /** Fails with [step] after dumping the semantics tree (when the app is shown) and the window hierarchy. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
        runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString().lines().forEach { Log.d(LOG_TAG, it) }
        }
        runCatching {
            val dump = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
            dump.lines().forEach { Log.d(LOG_TAG, it) }
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
        const val LOG_TAG = "PushE2eTest"

        /** gim-core's Invite notification body. */
        const val INVITE_BODY = "Added you to the chat"

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_RECOVERY_CODE = "onboarding_recovery_code"
        const val TAG_CODE_SAVED = "onboarding_code_saved"
        const val TAG_CODE_CONTINUE = "onboarding_code_continue"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_COMPOSER = "composer-input"
    }
}
