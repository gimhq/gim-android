package im.g.apps.e2e

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.testing.HubAdmin
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.onboardingShown
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.showServerField
import im.g.apps.testing.skipIntro
import im.g.apps.testing.openSettingsRow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.settings.R as SettingsR
import im.g.apps.feature.devices.R as DevicesR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * A hub refusing the signed-in app user, driven through gim-hub's admin API (instrumentation arguments `gimServerUrl`,
 * `gimAdminUrl`, `gimAdminToken`; skipped without the admin ones, e.g. in CI). [runRefusalFlow]: the user onboards and
 * chats with an in-process peer; [Refusal.apply] makes the hub refuse them; the next send fails with
 * [Refusal.cause] ("Not sent", no Retry) and the Devices screen (a call that needs the hub) shows
 * [Refusal.message]; the app stays signed in with its chats readable. [Refusal.lift] ends the refusal and the user
 * sends again from a reopened client ([im.g.apps.core.bridge.GimClientManager.reopen], what a process restart does):
 * gim-core does not sign in on a refusing hub again for 10 minutes within one client.
 */
abstract class RefusedUserE2eBase {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL
    private val adminUrl = args.getString("gimAdminUrl")?.takeIf { it.isNotBlank() }
    private val adminToken = args.getString("gimAdminToken")?.takeIf { it.isNotBlank() }

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    protected val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val groupName = "E2E $suffix"

    private val peerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-refused-peer-$suffix")
    private var peerClient: GimClient? = null
    private var admin: HubAdmin? = null
    private var appUserId: String? = null

    /** Log tag of the concrete test. */
    protected abstract val logTag: String

    /** How the hub refuses the app user, and how that ends. */
    protected interface Refusal {
        val cause: SendFailure

        @get:StringRes
        val message: Int

        fun apply(admin: HubAdmin, userId: String)

        /** Ends the refusal; runs again in teardown, so it must tolerate a refusal already lifted or never applied. */
        fun lift(admin: HubAdmin, userId: String)
    }

    protected abstract val refusal: Refusal

    @Before
    fun requireAdmin() {
        assumeTrue("needs instrumentation arguments gimAdminUrl and gimAdminToken", adminUrl != null && adminToken != null)
    }

    @After
    fun tearDown() {
        val admin = admin
        val userId = appUserId
        try {
            // Later classes share the hub: never leave the refusal in force.
            if (admin != null && userId != null) runCatching { refusal.lift(admin, userId) }.onFailure { Log.w(logTag, "lift", it) }
        } finally {
            peerScope.cancel()
            runBlocking {
                try {
                    peerClient?.signOut()
                } catch (_: GimException) {
                    // Local wipe below still runs.
                }
            }
            peerDir.deleteRecursively()
            runBlocking { (instrumentation.targetContext.applicationContext as GimApplication).container.signOut() }
        }
    }

    protected fun runRefusalFlow() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(logTag, "app window still has no focus; continuing")
        }
        val admin = HubAdmin.login(checkNotNull(adminUrl), checkNotNull(adminToken)).also { admin = it }

        onboard()
        val userId = checkNotNull(appClient().userId()).also { appUserId = it }
        Log.i(logTag, "app user $userId")

        // The peer opens a group with the app user; both sides chat.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        val welcome = "Welcome aboard $suffix"
        val chatId = runBlocking {
            peer.createIdentity("Peer $suffix")
            peer.claimHandle("peer_$suffix")
            peer.startSync()
            peer.createGroupChat(groupName, listOf(userId)).also { peer.sendText(it, welcome, null) }
        }
        val peerMessages = timeline(peer, chatId)
        val row = hasText(groupName) and hasClickAction()
        waitFor("app lists the group", row)
        click("open the group", row)
        waitForText("app shows the peer welcome", welcome)
        val before = "Leaving Hvar at dawn $suffix"
        send(before)
        peerMessages.await("peer receives the first app text") { !it.isOwn && it.text == before }

        // The hub refuses the user: the next send fails for good, without Retry.
        refusal.apply(admin, userId)
        Log.i(logTag, "refusal applied")
        val doomed = "Still there $suffix"
        send(doomed)
        val failed = timeline(appClient(), chatId).await("app message fails with ${refusal.cause}") {
            it.isOwn && it.text == doomed && it.sendState is SendState.Failed
        }
        assertEquals(refusal.cause, (failed.sendState as SendState.Failed).cause)
        waitForText("failed message shown as not sent", string(ChatR.string.chat_message_not_sent))
        assertFalse("Retry hint shown", exists(hasText(string(ChatR.string.chat_message_failed))))
        compose.onNode(hasText(doomed) and hasClickAction()).performTouchInput { longClick() }
        val delete = hasText(string(ChatR.string.composer_action_delete)) and hasClickAction()
        waitFor("failed message actions", delete)
        assertFalse("Retry offered", exists(hasText(string(ChatR.string.composer_action_retry))))
        UiDevice.getInstance(instrumentation).pressBack()
        waitFor("actions closed") { !exists(delete) }

        // A screen that needs the hub shows the refusal; the user stays signed in with the chats readable.
        UiDevice.getInstance(instrumentation).pressBack()
        waitForText("chat list", string(ChatListR.string.chatlist_title))
        compose.openSettingsRow(logTag, string(SettingsR.string.settings_linked_devices), timeoutMs, ::failStep)
        waitForText("devices screen shows the refusal", string(refusal.message))
        compose.onNodeWithContentDescription(string(DevicesR.string.devices_back)).performClick()
        waitForText("settings after the refusal", string(SettingsR.string.settings_title))
        compose.onNodeWithContentDescription(string(SettingsR.string.settings_back)).performClick()
        waitForText("chat list after the refusal", string(ChatListR.string.chatlist_title))
        assertFalse("onboarding shown", exists(compose.onboardingShown()))
        click("reopen the group", row)
        waitForText("earlier messages readable", before)
        waitForText("peer welcome readable", welcome)

        // Lifted: a reopened client (as after an app restart) signs in again and the user can send.
        refusal.lift(admin, userId)
        Log.i(logTag, "refusal lifted")
        UiDevice.getInstance(instrumentation).pressBack()
        waitForText("chat list before reopening the client", string(ChatListR.string.chatlist_title))
        runBlocking { checkNotNull((compose.activity.application as GimApplication).container.manager.reopen()) }
        click("open the group with the reopened client", row)
        val after = "Back on the water $suffix"
        send(after)
        peerMessages.await("peer receives the app text after the refusal") { !it.isOwn && it.text == after }
    }

    private fun onboard() {
        compose.skipIntro(logTag, timeoutMs, ::failStep)
        waitForText("onboarding shown", string(OnboardingR.string.onboarding_welcome_title))
        compose.showServerField(logTag, timeoutMs, ::failStep)
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "App $suffix")
        click("create account", TAG_SUBMIT)
        val creating = hasContentDescription(string(OnboardingR.string.onboarding_submitting))
        val recoveryCode = hasTestTag(TAG_RECOVERY_CODE)
        waitFor("identity creation started (loading or recovery code)") { exists(creating) || exists(recoveryCode) }
        compose.completeRecoveryKit(logTag, timeoutMs, ::failStep)
        enterText("handle", TAG_HANDLE, "app_$suffix")
        click("claim handle", TAG_CLAIM_HANDLE)
        waitForText("chat list after onboarding", string(ChatListR.string.chatlist_title))
    }

    private fun send(text: String) {
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(text)
        click("send '$text'", hasContentDescription(string(ChatR.string.composer_send)))
    }

    /** The app's own current gim-core client (the test runs in the app process). */
    private fun appClient(): GimClient =
        checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)

    private fun timeline(client: GimClient, chatId: String): StateFlow<ImmutableList<TimelineItem>> = runBlocking {
        client.timeline(chatId).itemsFlow().stateIn(peerScope, SharingStarted.Eagerly, persistentListOf())
    }

    private fun StateFlow<ImmutableList<TimelineItem>>.await(step: String, predicate: (MessageItem) -> Boolean): MessageItem =
        try {
            runBlocking {
                withTimeout(timeoutMs) { first { items -> items.messages().any(predicate) }.messages().first(predicate) }
            }
        } catch (e: TimeoutCancellationException) {
            failStep(step, e)
        }

    private fun List<TimelineItem>.messages(): List<MessageItem> =
        filterIsInstance<TimelineItem.Message>().map { it.item }

    private val MessageItem.text: String? get() = (content as? MessageContent.Text)?.body

    protected fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitFor(step) { exists(matcher) }

    private fun waitForText(step: String, text: String) = waitFor(step, hasText(text))

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

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

    private fun click(button: String, tag: String, next: SemanticsMatcher? = null) =
        compose.clickWhenSettled(logTag, button, tag, timeoutMs, next)

    private fun click(button: String, target: SemanticsMatcher) = compose.clickWhenSettled(logTag, button, target, timeoutMs)

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    /** Fails with [step] and what is on screen, after dumping the semantics tree to logcat under [logTag]. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$logTag-$step")
        val screen = runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots.printToString().lines().forEach { Log.d(logTag, it) }
            roots.fetchSemanticsNodes().flatMap { it.describe() }.distinct().joinToString(" | ")
        }.getOrElse { "unavailable: $it" }
        throw AssertionError("step '$step' timed out after $timeoutMs ms; on screen: [$screen]", cause)
    }

    private fun SemanticsNode.describe(): List<String> {
        val parts = buildList {
            config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            config.getOrNull(SemanticsProperties.EditableText)?.let { add("field='${it.text}'") }
            config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add("desc='$it'") }
        }
        val self = if (parts.isEmpty()) emptyList() else listOf(parts.joinToString(" "))
        return self + children.flatMap { it.describe() }
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

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_RECOVERY_CODE = "onboarding_recovery_code"
        const val TAG_HANDLE = "onboarding_handle"
        const val TAG_CLAIM_HANDLE = "onboarding_claim_handle"
        const val TAG_COMPOSER = "composer-input"
    }
}
