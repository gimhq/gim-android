package im.g.apps.e2e

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.platform.KeystoreSecretCipher
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.onboardingShown
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.skipIntro
import im.g.apps.testing.signOutFromSettings
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.contacts.R as ContactsR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Device-level messaging flow against a live gim-hub (instrumentation argument `gimServerUrl`): the app user
 * drives the real UI with the real gim-core client, a scripted peer is a second gim-core client in this process.
 */
@RunWith(AndroidJUnit4::class)
class MessagingE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val appHandle = "app_$suffix"
    private val appName = "App $suffix"
    private val peerHandle = "peer_$suffix"
    private val peerName = "Peer $suffix"
    private val groupName = "E2E $suffix"

    private val peerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The test runs in the app process, which cannot write to the test APK's own data dir.
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-peer-$suffix")

    /** Under the app's FileProvider `media` root so the stubbed photo picker can hand out a content URI. */
    private val pickedDir by lazy { File(compose.activity.cacheDir, "gim/media/e2e-$suffix") }
    private var peerClient: GimClient? = null

    @After
    fun tearDown() {
        peerScope.cancel()
        runBlocking {
            try {
                peerClient?.signOut()
            } catch (_: GimException) {
                // Local wipe below still runs.
            }
        }
        peerDir.deleteRecursively()
        pickedDir.deleteRecursively()
    }

    @Test
    fun messagingFlowThenSignOut() {
        keepScreenOn(instrumentation, compose.activity)
        // The emulator may not have a default network yet right after boot; registering then hangs.
        Log.i(LOG_TAG, "server URL $serverUrl")
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        Log.i(LOG_TAG, "$serverUrl/healthz returned 200")
        // A system "isn't responding" dialog right after boot keeps the app window from getting focus.
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // Onboarding (skip the intro, create identity, pass the recovery kit check, claim a handle) -> chat list.
        // Input goes through Compose semantics only (no Espresso root lookups, which wait for window focus); hide the
        // soft keyboard and scroll each button into view in case the keyboard covers it.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitForText("onboarding shown", string(OnboardingR.string.onboarding_welcome_title))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, appName)
        click("create account", TAG_SUBMIT)
        Log.i(LOG_TAG, "create account clicked")
        val creating = hasContentDescription(string(OnboardingR.string.onboarding_submitting))
        val recoveryCode = hasTestTag(TAG_RECOVERY_CODE)
        waitFor("identity creation started (loading or recovery code)") { exists(creating) || exists(recoveryCode) }
        val code = compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep).recoveryKey
        // Never log the code itself.
        assertTrue("recovery code (${code.length} chars) is not 39 symbols in groups of four", RECOVERY_CODE.matches(code))
        enterText("handle", TAG_HANDLE, appHandle)
        click("claim handle", TAG_CLAIM_HANDLE)
        Log.i(LOG_TAG, "claim handle clicked")
        val chatList = hasText(string(ChatListR.string.chatlist_title))
        waitFor("chat list after onboarding", chatList)
        Log.i(LOG_TAG, "chat list visible")

        // Peer creates its identity, claims a handle and syncs.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        runBlocking {
            peer.createIdentity(peerName)
            peer.claimHandle(peerHandle)
            peer.startSync()
        }

        // App creates a group with the peer.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_new_message)).performClick()
        waitForText("new message menu", string(ChatListR.string.chatlist_new_group))
        compose.onNodeWithText(string(ChatListR.string.chatlist_new_group)).performClick()
        waitFor("new group member search", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(peerHandle)
        val add = hasText(string(ContactsR.string.contacts_add)) and hasClickAction()
        waitFor("peer found by handle", add)
        click("add peer", add)
        click("next", hasText(string(ContactsR.string.contacts_next)))
        waitForText("group name step", string(ContactsR.string.contacts_create_group))
        compose.onNode(hasSetTextAction()).performTextInput(groupName)
        click("create group", hasText(string(ContactsR.string.contacts_create_group)))
        waitFor("group chat opened", hasTestTag(TAG_COMPOSER))

        val chatId = peerWait("peer sees the group") {
            peer.chatListFlow().first { list -> list.any { it.name == groupName } }.first { it.name == groupName }.chatId
        }
        val peerMessages = peerMessages(peer, chatId)

        // App sends a text; the peer receives it.
        val appText = "Anchored off Hydra $suffix"
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(appText)
        click("send", hasContentDescription(string(ChatR.string.composer_send)))
        val appMessage = peerMessages.await("peer receives the app text") {
            !it.isOwn && (it.content as? MessageContent.Text)?.body == appText
        }

        // Peer replies; the app shows the reply with its quoted preview.
        val replyText = "Swim at noon $suffix"
        val replyId = runBlocking { peer.sendText(chatId, replyText, appMessage.messageId) }
        waitForText("app shows the peer reply", replyText)
        // The quoted preview, not the app's own bubble, whose description also holds the text.
        waitFor("app shows the quoted preview", hasContentDescription(appText, substring = true) and !hasClickAction())

        // App reacts to the peer message; the peer sees it.
        compose.onNode(hasText(replyText) and hasClickAction()).performTouchInput { longClick() }
        val react = string(ChatR.string.composer_react, REACTION)
        waitFor("message actions sheet", hasContentDescription(react))
        compose.onNodeWithContentDescription(react).performClick()
        peerMessages.await("peer sees the reaction") {
            it.messageId == replyId && it.reactions.any { r -> r.emoji == REACTION && r.count >= 1 }
        }

        // Peer sends an image; the app shows the image row.
        val peerPhoto = copyAsset(File(peerDir, "peer-photo.png"))
        runBlocking { peer.sendMedia(chatId, peerPhoto.path, PNG_MIME, null, null) }
        waitFor("app shows the peer image", hasContentDescription(string(ChatR.string.chat_image)))

        // App sends an image through the stubbed photo picker; the peer receives it.
        val picked = copyAsset(File(pickedDir, "app-photo.png"))
        val pickedUri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.files", picked)
        Intents.init()
        try {
            intending(not(isInternal())).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(pickedUri)))
            click("attach", hasContentDescription(string(ChatR.string.composer_attach)))
            waitForText("attach menu", string(ChatR.string.composer_attach_photo))
            compose.onNodeWithText(string(ChatR.string.composer_attach_photo)).performClick()
            val appImage = peerMessages.await("peer receives the app image") {
                !it.isOwn && it.content is MessageContent.Image
            }

            // Peer marks the app's latest message read; the app shows the read tick.
            runBlocking { peer.markRead(chatId, appImage.messageId) }
            waitFor("app shows the read tick", hasContentDescription(string(DesignR.string.gim_tick_read)))
        } finally {
            Intents.release()
        }

        assertComposerDockedOnIme()

        // A direct chat with a well-formed but unknown user id: "User not found", no chat created.
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()
        waitForText("chat list", string(ChatListR.string.chatlist_title))
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_new_message)).performClick()
        waitForText("new message menu", string(ChatListR.string.chatlist_new_chat))
        compose.onNodeWithText(string(ChatListR.string.chatlist_new_chat)).performClick()
        waitFor("new chat search", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(UNKNOWN_USER_ID)
        val startChat = hasText(string(ContactsR.string.contacts_start_chat)) and hasClickAction()
        waitFor("start chat offered for the user id") { exists(startChat) && compose.onNode(startChat).isEnabled() }
        click("start chat", startChat)
        waitForText("user not found", string(ContactsR.string.contacts_error_user_not_found))
        compose.onNodeWithContentDescription(string(ContactsR.string.contacts_back)).performClick()
        waitForText("chat list after user not found", string(ChatListR.string.chatlist_title))
        assertFalse(exists(hasText(UNKNOWN_USER_ID.removePrefix("u").take(8), substring = true)))

        rejectedSendShowsFailedThenRemoved(peer)

        // Leave the group: "Leaving…" with the composer disabled until the peer commits the leave, then `Left`.
        compose.onNode(hasText(groupName) and hasClickAction()).performClick()
        waitFor("group chat reopened", hasTestTag(TAG_COMPOSER))
        // Hold the peer's sync so the commit (and the own `Left`) cannot arrive before "Leaving…" is checked.
        runBlocking { peer.stopSync() }
        compose.onNodeWithContentDescription(string(ChatR.string.chat_group_info)).performClick()
        waitForText("group info", string(ContactsR.string.contacts_leave_group))
        compose.onNode(hasText(string(ContactsR.string.contacts_leave_group)) and hasClickAction()).performScrollTo().performClick()
        val confirmLeave = hasText(string(ContactsR.string.contacts_leave)) and hasClickAction()
        waitFor("leave confirmation", confirmLeave)
        compose.onNode(confirmLeave).performClick()
        waitFor("composer shows leaving", hasTestTag(TAG_BANNER) and hasText(string(ChatR.string.chat_composer_leaving)))
        compose.onNodeWithTag(TAG_COMPOSER).assertIsNotEnabled()
        // The chat list row shows the state too.
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()
        waitForText("chat list shows leaving", string(ChatListR.string.chatlist_state_leaving))
        compose.onNode(hasText(groupName) and hasClickAction()).performClick()
        waitFor("group chat reopened while leaving", hasTestTag(TAG_COMPOSER))
        runBlocking { peer.startSync() }
        waitForText("app shows its own left change", string(ChatR.string.chat_member_you_left))
        waitFor("composer shows left", hasTestTag(TAG_BANNER) and hasText(string(ChatR.string.chat_composer_left)))
        compose.onNodeWithTag(TAG_COMPOSER).assertIsNotEnabled()

        // Sign out through Settings.
        assertTrue(keystoreHasAlias())
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()
        waitForText("chat list before sign-out", string(ChatListR.string.chatlist_title))
        compose.signOutFromSettings(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding after sign-out", compose.onboardingShown())
        assertFalse(keystoreHasAlias())
    }

    /**
     * A send the hub rejects with 403 `not_member` shows Failed at once, with Retry and Delete: the peer creates a
     * second group with the app user and removes it while the app's sync is stopped, so the app still believes it is a
     * member when it sends. Once sync resumes the removal arrives: "You were removed" and a read-only composer.
     */
    private fun rejectedSendShowsFailedThenRemoved(peer: GimClient) {
        val app = appClient()
        val appUserId = checkNotNull(app.userId())
        val removedGroup = "E2E removed $suffix"
        val welcome = "Welcome aboard $suffix"
        val chatId = runBlocking {
            peer.createGroupChat(removedGroup, listOf(appUserId)).also { peer.sendText(it, welcome, null) }
        }
        val row = hasText(removedGroup) and hasClickAction()
        waitFor("app lists the second group", row)
        click("open the second group", row)
        waitForText("app shows the peer welcome", welcome)

        // Hold the app's sync (and with it the removal), then the peer removes the app user.
        runBlocking { withContext(Dispatchers.IO) { app.stopSync() } }
        peerWait("peer removes the app user") { peer.removeMember(chatId, appUserId) }

        val doomed = "Anyone here $suffix"
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(doomed)
        click("send to the removed group", hasContentDescription(string(ChatR.string.composer_send)))
        waitFor("app shows the rejected send as failed", hasContentDescription(string(DesignR.string.gim_tick_failed)))
        val failed = appMessages(app, chatId).await("app message is failed") {
            it.isOwn && (it.content as? MessageContent.Text)?.body == doomed && it.sendState is SendState.Failed
        }
        val reason = (failed.sendState as SendState.Failed).reason
        Log.i(LOG_TAG, "rejected send failed with: $reason")
        assertTrue("send failed with '$reason', not the hub's 403 not_member", "403 not_member" in reason)

        // Retry and Delete are offered; Delete drops the message.
        compose.onNode(hasText(doomed) and hasClickAction()).performTouchInput { longClick() }
        waitForText("failed message actions", string(ChatR.string.composer_action_retry))
        val delete = hasText(string(ChatR.string.composer_action_delete)) and hasClickAction()
        waitFor("delete offered for the failed message", delete)
        click("delete failed message", delete)
        waitFor("failed message deleted") { !exists(hasText(doomed)) }

        // Sync resumes: the removal arrives and the chat becomes read-only.
        runBlocking { withContext(Dispatchers.IO) { app.startSync() } }
        waitForText("app shows its removal", string(ChatR.string.chat_member_removed_you))
        waitFor("composer shows removed", hasTestTag(TAG_BANNER) and hasText(string(ChatR.string.chat_composer_removed)))
        compose.onNodeWithTag(TAG_COMPOSER).assertIsNotEnabled()
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()
        waitForText("chat list shows the removed group as left", string(ChatListR.string.chatlist_state_left))
    }

    /** The app's own gim-core client (the test runs in the app process). */
    private fun appClient(): GimClient =
        checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)

    private fun appMessages(app: GimClient, chatId: String): StateFlow<ImmutableList<TimelineItem>> = runBlocking {
        app.timeline(chatId).itemsFlow().stateIn(peerScope, SharingStarted.Eagerly, persistentListOf())
    }

    /** Messages of [chatId] as the peer sees them, kept current by one timeline subscription. */
    private fun peerMessages(peer: GimClient, chatId: String): StateFlow<ImmutableList<TimelineItem>> = runBlocking {
        val timeline = peer.timeline(chatId)
        timeline.itemsFlow().stateIn(peerScope, SharingStarted.Eagerly, persistentListOf())
    }

    private fun StateFlow<ImmutableList<TimelineItem>>.await(
        step: String,
        predicate: (MessageItem) -> Boolean,
    ): MessageItem = peerWait(step) { first { items -> items.messages().any(predicate) }.messages().first(predicate) }

    /** Runs a peer-side wait bounded by [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> peerWait(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
    }

    private fun List<TimelineItem>.messages(): List<MessageItem> =
        filterIsInstance<TimelineItem.Message>().map { it.item }

    private fun copyAsset(target: File): File {
        target.parentFile?.mkdirs()
        instrumentation.context.assets.open(PHOTO_ASSET).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun keystoreHasAlias(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreSecretCipher.KEY_ALIAS)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitFor(step) { exists(matcher) }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteraction.isEnabled() = SemanticsProperties.Disabled !in fetchSemanticsNode().config

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

    private fun waitForText(step: String, text: String) = waitFor(step, hasText(text))

    /** Clicks the enabled, displayed button tagged [tag] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, tag: String, next: SemanticsMatcher? = null) =
        compose.clickWhenSettled(LOG_TAG, button, tag, timeoutMs, next)

    /** Clicks the single node matching [target] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, target: SemanticsMatcher) = compose.clickWhenSettled(LOG_TAG, button, target, timeoutMs)

    /**
     * Focuses the composer and, once the soft keyboard is fully shown, checks the composer sits right on top of
     * it (the IME inset applied exactly once). Skipped with a warning when no soft keyboard appears (emulators
     * with a hardware keyboard). Instrumentation argument `screenshotDir` saves the screen with the keyboard open.
     */
    private fun assertComposerDockedOnIme() {
        compose.onNodeWithTag(TAG_COMPOSER).performClick()
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        var imeBottom = 0
        val settled = runCatching {
            compose.waitUntil(timeoutMs) {
                val previous = imeBottom
                imeBottom = imeInsetBottom()
                Thread.sleep(IME_SETTLE_POLL_MS)
                imeBottom > 0 && imeBottom == previous && imeBottom == imeInsetBottom()
            }
        }.isSuccess
        if (!settled) {
            Log.w(LOG_TAG, "no soft keyboard shown; skipping the composer docking check")
            hideSoftKeyboard()
            return
        }
        compose.waitForIdle()
        args.getString("screenshotDir")?.let { dir ->
            val file = File(dir, "composer-ime-$suffix.png").apply { parentFile?.mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Log.i(LOG_TAG, "IME screenshot: $file")
        }
        var windowHeight = 0
        instrumentation.runOnMainSync { windowHeight = compose.activity.window.decorView.height }
        val imeTop = windowHeight - imeBottom
        val fieldBottom = compose.onNodeWithTag(TAG_COMPOSER).fetchSemanticsNode().boundsInWindow.bottom
        val density = compose.activity.resources.displayMetrics.density
        // The input field sits inside the composer's 4dp vertical padding.
        val expectedGap = 4 * density
        val gap = imeTop - fieldBottom
        Log.i(LOG_TAG, "IME top $imeTop px, composer field bottom $fieldBottom px, gap $gap px")
        assertTrue(
            "composer field bottom $fieldBottom px is not docked on the IME top $imeTop px (gap $gap px)",
            abs(gap - expectedGap) <= IME_DOCK_TOLERANCE_PX,
        )
        hideSoftKeyboard()
    }

    private fun imeInsetBottom(): Int {
        var bottom = 0
        instrumentation.runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    private fun hideSoftKeyboard() {
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    /**
     * Fails with [step] and what is on screen (texts, field values, content descriptions such as the onboarding
     * progress indicator, disabled / focused flags), after dumping the semantics tree of every window to logcat
     * under [LOG_TAG], one line per entry because logcat truncates long entries.
     */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
        val screen = runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots.printToString().lines().forEach { Log.d(LOG_TAG, it) }
            roots.fetchSemanticsNodes().flatMap { it.describe() }.distinct().joinToString(" | ")
        }.getOrElse { "unavailable: $it" }
        val focus = runCatching { appHasFocus() }.getOrElse { "unknown" }
        throw AssertionError("step '$step' timed out after $timeoutMs ms; app focused: $focus; on screen: [$screen]", cause)
    }

    private fun SemanticsNode.describe(): List<String> {
        val parts = buildList {
            config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            config.getOrNull(SemanticsProperties.EditableText)?.let { add("field='${it.text}'") }
            config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add("desc='$it'") }
        }
        val flags = buildList {
            if (SemanticsProperties.Disabled in config) add("disabled")
            if (config.getOrNull(SemanticsProperties.Focused) == true) add("focused")
        }
        val self = if (parts.isEmpty()) emptyList() else listOf(parts.joinToString(" ") + flags.joinToString("") { " [$it]" })
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
        const val LOG_TAG = "MessagingE2eTest"
        const val REACTION = "👍"
        const val PHOTO_ASSET = "e2e-photo.png"
        const val PNG_MIME = "image/png"
        const val IME_SETTLE_POLL_MS = 300L
        const val IME_DOCK_TOLERANCE_PX = 4f

        /** Well-formed (u + 55 base32 characters) but unknown to the hub. */
        const val UNKNOWN_USER_ID = "u" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" + "a"

        /** gim-core's display form: 39 Crockford base32 symbols in blocks of four separated by '-'. */
        val RECOVERY_CODE = Regex("([0-9A-HJKMNP-TV-Z]{4}-){9}[0-9A-HJKMNP-TV-Z]{3}")

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_RECOVERY_CODE = "onboarding_recovery_code"
        const val TAG_HANDLE = "onboarding_handle"
        const val TAG_CLAIM_HANDLE = "onboarding_claim_handle"
        const val TAG_COMPOSER = "composer-input"
        const val TAG_BANNER = "composer-membership-banner"
    }
}
