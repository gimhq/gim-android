package im.g.apps.e2e

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.contacts.R as ContactsR

/**
 * Blocking against a live gim-hub (instrumentation argument `gimServerUrl`): a scripted peer (a second gim-core
 * client in this process) opens a direct chat with the app user. The app blocks the peer from chat info: the chat
 * shows Unblock instead of the composer and the peer's next message is dropped. Unblocking brings the composer
 * back and the peer's next message arrives; the dropped one stays gone.
 */
@RunWith(AndroidJUnit4::class)
class BlockE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val app get() = instrumentation.targetContext.applicationContext as GimApplication

    // The test runs in the app process, which cannot write to the test APK's own data dir.
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-block-peer-$suffix")
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
            app.container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun blockFromChatInfoThenUnblock() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // Onboarding: skip the intro, create an account, pass the recovery kit check, skip the username.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        enterText("server address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Block $suffix")
        click("create account", TAG_SUBMIT)
        compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitFor("username step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip username", TAG_SKIP_HANDLE)
        waitForText("chat list", string(ChatListR.string.chatlist_title))
        val appUserId = checkNotNull(checkNotNull(app.container.manager.client.value).userId())

        // The peer opens a direct chat with the app user and says hello.
        val peerName = "Peer $suffix"
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        val hello = "Hello $suffix"
        val chatId = runBlocking {
            peer.createIdentity(peerName)
            peer.startSync()
            peer.createDirectChat(appUserId).also { peer.sendText(it, hello, null) }
        }
        val row = hasText(peerName) and hasClickAction()
        waitFor("app lists the direct chat", row)
        click("open the direct chat", row)
        waitForText("app shows the hello", hello)
        waitFor("composer shown", hasTestTag(TAG_COMPOSER))

        // Block from chat info, behind a confirmation.
        click("contact info", hasContentDescription(string(ChatR.string.chat_contact_info)) and hasClickAction())
        waitFor("chat info block action", hasTestTag(TAG_ACTION_BLOCK))
        compose.onNodeWithTag(TAG_ACTION_BLOCK).performScrollTo()
        click("block", TAG_ACTION_BLOCK, next = hasTestTag(TAG_BLOCK_CONFIRM))
        click(
            "confirm block",
            hasText(string(ContactsR.string.contacts_chat_action_block)) and hasClickAction() and hasAnyAncestor(isDialog()),
        )
        waitFor("chat info offers unblock", hasTestTag(TAG_ACTION_UNBLOCK))
        click("back to the chat", hasContentDescription(string(ContactsR.string.contacts_back)) and hasClickAction())

        // The chat shows Unblock instead of the composer, and the peer's next message is dropped.
        waitFor("unblock bar shown", hasTestTag(TAG_UNBLOCK))
        waitForText("blocked notice", string(ChatR.string.chat_blocked))
        assertGone("composer hidden while blocked", hasTestTag(TAG_COMPOSER))
        val dropped = "While blocked $suffix"
        runBlocking { peer.sendText(chatId, dropped, null) }
        // Nothing to wait for: give the app time to receive and drop it, then check it is not shown.
        Thread.sleep(DROP_WAIT_MS)
        assertGone("message from the blocked peer not shown", hasText(dropped))

        // Unblock from the chat, behind a confirmation: the composer is back and new messages arrive.
        click("unblock", TAG_UNBLOCK)
        click("confirm unblock", hasText(string(ChatR.string.chat_unblock)) and hasClickAction() and hasAnyAncestor(isDialog()))
        waitFor("composer back after unblock", hasTestTag(TAG_COMPOSER))
        assertGone("unblock bar hidden after unblock", hasTestTag(TAG_UNBLOCK))
        val after = "After unblock $suffix"
        runBlocking { peer.sendText(chatId, after, null) }
        waitForText("app shows the message sent after unblock", after)
        // It was sent after the dropped one, so the dropped one has been processed by now too.
        assertGone("dropped message does not come back", hasText(dropped))
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun assertGone(step: String, matcher: SemanticsMatcher) {
        if (exists(matcher)) failStep(step, AssertionError("still on screen"))
    }

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitFor(step) { exists(matcher) }

    private fun waitForText(step: String, text: String) = waitFor(step, hasText(text))

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

    /** Clicks the single node matching [target] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, target: SemanticsMatcher) = compose.clickWhenSettled(LOG_TAG, button, target, timeoutMs)

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    /** Fails with [step] after a screenshot and a semantics dump to logcat under [LOG_TAG]. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
        runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString().lines().forEach { Log.d(LOG_TAG, it) }
        }
        throw AssertionError("step '$step' failed (timeout $timeoutMs ms)", cause)
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
        const val DROP_WAIT_MS = 5_000L
        const val LOG_TAG = "BlockE2eTest"

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG,
        // UNBLOCK_BUTTON_TEST_TAG, ChatActionsTestTags).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_COMPOSER = "composer-input"
        const val TAG_UNBLOCK = "chat-unblock"
        const val TAG_ACTION_BLOCK = "chat_action_block"
        const val TAG_ACTION_UNBLOCK = "chat_action_unblock"
        const val TAG_BLOCK_CONFIRM = "chat_actions_block_confirm"
    }
}
