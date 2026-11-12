package im.g.apps.e2e

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.core.designsystem.explainer.accountIntroPoints
import im.g.apps.testing.OnboardingTags
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.showServerField
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.contacts.R as ContactsR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * A new user's first minutes against a live gim-hub (instrumentation argument `gimServerUrl`): every intro page with
 * Next and Get started, a new account, the recovery kit and its check, then a direct chat with an
 * in-process peer (a second gim-core client, as in [MessagingE2eTest]) and the first message, shown as sent and
 * received by the peer.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingToFirstMessageE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)

    // The test runs in the app process, which cannot write to the test APK's own data dir.
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-first-peer-$suffix")
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
            (instrumentation.targetContext.applicationContext as GimApplication).container.signOut()
        }
        peerDir.deleteRecursively()
    }

    @Test
    fun introToFirstDirectMessage() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // The peer: an identity, syncing.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        runBlocking {
            peer.createIdentity("Peer $suffix")
            peer.startSync()
        }

        // Intro: each page shows its heading; Next on every page but the last, then Get started.
        accountIntroPoints.forEachIndexed { index, point ->
            val page = index + 1
            val dots = string(OnboardingR.string.onboarding_intro_page, page, accountIntroPoints.size)
            waitFor("intro page $page", hasContentDescription(dots))
            waitForText("intro page $page heading", string(point.title))
            compose.onNode(hasText(string(point.title))).assertIsDisplayed()
            if (index < accountIntroPoints.lastIndex) {
                // No `next`: Next stays enabled on the following page, so a retry would skip a page.
                click("intro next", OnboardingTags.INTRO_NEXT)
            }
        }
        click("get started", OnboardingTags.INTRO_START, next = hasTestTag(TAG_CREATE_ACCOUNT))

        // Welcome -> name -> recovery kit (heading shown) -> check -> chat list.
        waitForText("welcome", string(OnboardingR.string.onboarding_welcome_title))
        compose.showServerField(LOG_TAG, timeoutMs, ::failStep)
        enterText("server address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "First $suffix")
        click("create account", TAG_SUBMIT)
        waitForText("recovery kit heading", string(OnboardingR.string.onboarding_kit_title))
        val kit = compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitForText("chat list after onboarding", string(ChatListR.string.chatlist_title))
        assertEquals(kit.accountId, appClient().userId())

        // New chat with the peer's Account ID.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_new_message)).performClick()
        waitForText("new message menu", string(ChatListR.string.chatlist_new_chat))
        compose.onNodeWithText(string(ChatListR.string.chatlist_new_chat)).performClick()
        waitFor("new chat search", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(checkNotNull(peer.userId()))
        val startChat = hasText(string(ContactsR.string.contacts_start_chat)) and hasClickAction() and isEnabled()
        waitFor("peer taken by Account ID", startChat)
        click("start chat", startChat)
        waitFor("direct chat opened", hasTestTag(TAG_COMPOSER))

        // The first message: shown as sent (or delivered) in the app, received by the peer.
        val text = "Hello from a new account $suffix"
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(text)
        click("send", hasContentDescription(string(ChatR.string.composer_send)))
        waitFor("first message shown", hasText(text) and !hasSetTextAction())
        val sent = hasContentDescription(string(DesignR.string.gim_tick_sent)) or
            hasContentDescription(string(DesignR.string.gim_tick_delivered))
        waitFor("first message shown as sent", sent)
        val chatId = peerWait("peer sees the direct chat") {
            val direct = { chat: ChatSummary -> chat.kind == ChatKind.DIRECT }
            peer.chatListFlow().first { list -> list.any(direct) }.first(direct).chatId
        }
        peerWait("peer receives the first message") {
            peer.timeline(chatId).itemsFlow().first { items ->
                items.filterIsInstance<TimelineItem.Message>().map { it.item }.any { it.isFromAppWith(text) }
            }
        }
    }

    private fun MessageItem.isFromAppWith(text: String) = !isOwn && (content as? MessageContent.Text)?.body == text

    /** The app's own gim-core client (the test runs in the app process). */
    private fun appClient(): GimClient =
        checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)

    /** Runs a peer-side wait bounded by [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> peerWait(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) =
        waitFor(step) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(step: String, text: String) = waitFor(step, hasText(text))

    /** Replaces the text of the field tagged [tag] and waits until the field holds exactly [value]. */
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

    /** Fails with [step] and what is on screen, after dumping the semantics tree to logcat under [LOG_TAG]. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
        val screen = runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots.printToString().lines().forEach { Log.d(LOG_TAG, it) }
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
        const val LOG_TAG = "OnboardingToFirstMessageE2eTest"

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_COMPOSER = "composer-input"
    }
}
