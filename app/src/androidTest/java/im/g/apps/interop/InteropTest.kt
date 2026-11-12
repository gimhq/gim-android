package im.g.apps.interop

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.contacts.R as ContactsR
import im.g.apps.feature.onboarding.R as OnboardingR
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.showServerField
import im.g.apps.testing.skipIntro
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android side of scripts/e2e-interop.sh: the app user ("android") drives the real UI against the hub while the
 * script plays alice and bob with the gim CLI. Skipped unless the script passes `interopRun`.
 *
 * Instrumentation arguments: `gimServerUrl`, `interopRun` (suffix of every text below), `aliceName`,
 * `aliceUserId`, `bobName`, `androidName`. Each [Rendezvous.mark] hands over to the script; the test
 * then waits on UI state the CLI side produces. Message texts must match the script's.
 */
@RunWith(AndroidJUnit4::class)
class InteropTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val run = args.getString("interopRun").orEmpty()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL
    private val aliceName = args.getString("aliceName").orEmpty()
    private val bobName = args.getString("bobName").orEmpty()
    private val aliceUserId = args.getString("aliceUserId").orEmpty()
    private val androidName = args.getString("androidName").orEmpty()

    private lateinit var rendezvous: Rendezvous

    /** Under the app's FileProvider `media` root so the stubbed photo picker can hand out a content URI. */
    private val pickedDir by lazy { File(compose.activity.cacheDir, "gim/media/interop-$run") }

    @Before
    fun setUp() {
        assumeTrue("run by scripts/e2e-interop.sh only", run.isNotEmpty())
        rendezvous = Rendezvous(instrumentation.targetContext)
    }

    @After
    fun tearDown() {
        if (::rendezvous.isInitialized) rendezvous.close()
        if (run.isNotEmpty()) pickedDir.deleteRecursively()
    }

    @Test
    fun interopWithCliPeers() {
        keepScreenOn(instrumentation, compose.activity)
        // a. Skip the intro, create the identity through onboarding and pass the recovery kit check, then report the
        // app's Account ID, which the CLI addresses it by. Buttons are scrolled into view with the soft keyboard
        // hidden, which can cover them.
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        compose.skipIntro(Rendezvous.TAG, TIMEOUT_MS, ::fail)
        compose.waitForText(string(OnboardingR.string.onboarding_welcome_title))
        compose.showServerField(Rendezvous.TAG, TIMEOUT_MS, ::fail)
        compose.onNodeWithTag(TAG_SERVER_URL).performTextReplacement(serverUrl)
        click(TAG_CREATE_ACCOUNT)
        compose.waitFor(hasTestTag(TAG_DISPLAY_NAME))
        compose.onNodeWithTag(TAG_DISPLAY_NAME).performTextReplacement(androidName)
        click(TAG_SUBMIT)
        compose.completeRecoveryKit(Rendezvous.TAG, TIMEOUT_MS, ::fail)
        compose.waitForText(string(ChatListR.string.chatlist_title))
        val client = checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)
        rendezvous.value("android-user-id", checkNotNull(client.userId()))
        rendezvous.mark("registered")

        // b. alice's DM shows up, with her Account ID from her CLI JSON as its peer; reply; alice reads the reply ->
        // read tick.
        openChat(aliceName)
        val dmPeers = runBlocking { client.chatListFlow().first() }.filter { it.kind == ChatKind.DIRECT }.map { it.peer?.userId }
        assertEquals("peer of alice's DM", listOf(aliceUserId), dmPeers)
        compose.waitForText(text("Ahoy from alice"))
        send(text("Ahoy from android"))
        rendezvous.mark("dm-replied")
        compose.waitFor(hasContentDescription(string(DesignR.string.gim_tick_read)))
        rendezvous.mark("dm-read-tick")
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()

        // c. bob's group shows up with his welcome.
        openChat(text("Crew"))
        compose.waitForText(text("Welcome aboard"))
        rendezvous.mark("group-open")

        // d. Text both ways, image from the CLI, image from the app.
        send(text("Android in the group"))
        compose.waitForText(text("Alice in the group"))
        compose.waitForText(text("Bob photo"))
        compose.waitFor(hasContentDescription(string(ChatR.string.chat_image)))
        rendezvous.mark("cli-image-shown")
        sendPhoto(caption = text("Android photo"))
        rendezvous.mark("photo-sent")

        // e. Reply both ways (quoted preview of the app's message).
        compose.waitForText(text("Alice replies"))
        // The quoted preview, not the app's own bubble, whose description also holds the text.
        compose.waitFor(hasContentDescription(text("Android in the group"), substring = true) and !hasClickAction())
        messageAction(text("Alice replies"), string(ChatR.string.composer_action_reply))
        send(text("Android replies"))
        rendezvous.mark("android-replied")

        // Reactions both ways.
        compose.waitFor(hasContentDescription(reactionDescription(CLI_REACTION)))
        messageAction(text("Alice replies"), string(ChatR.string.composer_react, APP_REACTION))
        rendezvous.mark("android-reacted")

        // Edits both ways.
        compose.waitForText(text("Alice edited"))
        compose.waitFor(hasText(string(ChatR.string.chat_message_edited)))
        messageAction(text("Android replies"), string(ChatR.string.composer_action_edit))
        compose.onNodeWithTag(TAG_COMPOSER).performTextReplacement(text("Android edited"))
        compose.onNodeWithContentDescription(string(ChatR.string.composer_save)).performClick()
        compose.waitForText(text("Android edited"))
        rendezvous.mark("android-edited")

        // Deletes both ways.
        compose.waitForText(string(ChatR.string.chat_message_deleted))
        compose.waitGone(hasText(text("Alice edited")))
        messageAction(text("Android edited"), string(ChatR.string.composer_action_delete))
        compose.waitForText(string(ChatR.string.composer_delete_title))
        // The dialog's confirm button, once the sheet's action has gone.
        val delete = hasText(string(ChatR.string.composer_action_delete))
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(delete).fetchSemanticsNodes().size == 1 }
        compose.onNode(delete).performClick()
        compose.waitGone(hasText(text("Android edited")))
        rendezvous.mark("android-deleted")

        // Typing both ways: alice types -> indicator; the script listens, then the app types.
        rendezvous.mark("typing-ready")
        compose.waitFor(
            hasText(string(ChatR.string.chat_typing_one, aliceName)) or hasText(string(ChatR.string.chat_typing_someone)),
        )
        rendezvous.await("cli-listening", TIMEOUT_MS)
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(text("Android is typing"))
        rendezvous.mark("android-typing")
        rendezvous.await("typing-seen", TIMEOUT_MS)
        compose.onNodeWithTag(TAG_COMPOSER).performTextReplacement("")

        // f. Rename through Group info.
        compose.onNodeWithContentDescription(string(ChatR.string.chat_group_info)).performClick()
        compose.waitForText(string(ContactsR.string.contacts_rename_group))
        compose.onNodeWithText(string(ContactsR.string.contacts_rename_group)).performClick()
        compose.waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(text("Crew renamed"))
        compose.onNodeWithText(string(ContactsR.string.contacts_save)).performClick()
        compose.waitForText(text("Crew renamed"))
        rendezvous.mark("renamed")
        compose.onNodeWithContentDescription(string(ContactsR.string.contacts_back)).performClick()
        compose.waitFor(hasTestTag(TAG_COMPOSER))

        // g. bob removes alice; messages keep flowing between bob and the app.
        compose.waitFor(hasText(string(ChatR.string.chat_member_removed_by, bobName, aliceName)))
        compose.waitForText(text("Bob after removal"))
        send(text("Android after removal"))
        rendezvous.mark("android-after-removal")
    }

    private fun text(phrase: String) = "$phrase $run"

    /** Hides the soft keyboard, then scrolls to and clicks the button tagged [tag]. */
    private fun click(tag: String) {
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun openChat(title: String) {
        val row = hasText(title) and hasClickAction()
        compose.waitFor(row)
        compose.onNode(row).performClick()
        compose.waitFor(hasTestTag(TAG_COMPOSER))
    }

    private fun send(body: String) {
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(body)
        compose.onNodeWithContentDescription(string(ChatR.string.composer_send)).performClick()
        compose.waitForText(body)
    }

    /** Long-presses the message showing [body] and picks the sheet action described or labelled [action]. */
    private fun messageAction(body: String, action: String) {
        compose.onNode(hasText(body) and hasClickAction()).performTouchInput { longClick() }
        val item = hasText(action) or hasContentDescription(action)
        compose.waitFor(item)
        compose.onNode(item).performClick()
    }

    /** Sends the test PNG with [caption] (the draft) through the stubbed photo picker. */
    private fun sendPhoto(caption: String) {
        val picked = File(pickedDir, "android-photo.png").apply { parentFile?.mkdirs() }
        instrumentation.context.assets.open(PHOTO_ASSET).use { input -> picked.outputStream().use { input.copyTo(it) } }
        val pickedUri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.files", picked)
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(caption)
        Intents.init()
        try {
            intending(not(isInternal())).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(pickedUri)))
            compose.onNodeWithContentDescription(string(ChatR.string.composer_attach)).performClick()
            compose.waitForText(string(ChatR.string.composer_attach_photo))
            compose.onNodeWithText(string(ChatR.string.composer_attach_photo)).performClick()
            compose.waitFor(hasText(caption) and hasClickAction())
        } finally {
            Intents.release()
        }
    }

    private fun reactionDescription(emoji: String): String =
        compose.activity.resources.getQuantityString(DesignR.plurals.gim_reaction, 1, emoji, 1)

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String = compose.activity.getString(id, *formatArgs)

    private fun ComposeTestRule.waitFor(matcher: SemanticsMatcher) =
        waitOrFail("${matcher.description} shown") { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.waitGone(matcher: SemanticsMatcher) =
        waitOrFail("${matcher.description} gone") { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** Waits for [condition]; on timeout fails with [what] and the texts on screen, also logged for the script. */
    private fun ComposeTestRule.waitOrFail(what: String, condition: () -> Boolean) {
        try {
            waitUntil(TIMEOUT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            fail(what, e)
        }
    }

    private fun fail(what: String, cause: Throwable): Nothing {
        val screen = runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.texts() }.distinct()
        }.getOrElse { listOf("unavailable: $it") }
        val message = "timed out after $TIMEOUT_MS ms waiting for $what; on screen: $screen"
        Log.e(Rendezvous.TAG, "FAIL $message")
        throw AssertionError(message, cause)
    }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            children.flatMap { it.texts() }

    /** Shown [text], not an input field's content (the composer or the rename field may hold it too). */
    private fun ComposeTestRule.waitForText(text: String) = waitFor(hasText(text) and !hasSetTextAction())

    private companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val TIMEOUT_MS = 60_000L
        const val CLI_REACTION = "⛵"
        const val APP_REACTION = "👍"
        const val PHOTO_ASSET = "e2e-photo.png"

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_COMPOSER = "composer-input"
    }
}
