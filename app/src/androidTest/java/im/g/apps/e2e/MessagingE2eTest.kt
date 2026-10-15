package im.g.apps.e2e

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.MainActivity
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.platform.KeystoreSecretCipher
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
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
 * Device-level messaging flow against a live gim-server (instrumentation argument `gimServerUrl`): the app user
 * drives the real UI with the real gim-core client, a scripted peer is a second gim-core client in this process.
 */
@RunWith(AndroidJUnit4::class)
class MessagingE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val serverUrl = InstrumentationRegistry.getArguments().getString("gimServerUrl") ?: DEFAULT_SERVER_URL
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
        // Onboarding -> chat list.
        compose.waitForText(string(OnboardingR.string.onboarding_title))
        compose.onNodeWithTag(TAG_SERVER_URL).performTextReplacement(serverUrl)
        compose.onNodeWithTag(TAG_HANDLE).performTextInput(appHandle)
        compose.onNodeWithTag(TAG_DISPLAY_NAME).performTextInput(appName)
        compose.onNodeWithTag(TAG_SUBMIT).performClick()
        compose.waitForText(string(ChatListR.string.chatlist_title))

        // Peer registers and syncs.
        val peer = runBlocking {
            UniffiGimClientFactory().create(ClientConfig(serverUrl), InMemoryHooks(peerDir)).also { peerClient = it }
        }
        runBlocking {
            peer.register(peerHandle, peerName)
            peer.startSync()
        }

        // App creates a group with the peer.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_new_message)).performClick()
        compose.waitForText(string(ChatListR.string.chatlist_new_group))
        compose.onNodeWithText(string(ChatListR.string.chatlist_new_group)).performClick()
        compose.waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(peerHandle)
        val add = hasText(string(ContactsR.string.contacts_add)) and hasClickAction()
        compose.waitFor(add)
        compose.onNode(add).performClick()
        compose.onNodeWithText(string(ContactsR.string.contacts_next)).performClick()
        compose.waitForText(string(ContactsR.string.contacts_create_group))
        compose.onNode(hasSetTextAction()).performTextInput(groupName)
        compose.onNodeWithText(string(ContactsR.string.contacts_create_group)).performClick()
        compose.waitFor(hasTestTag(TAG_COMPOSER))

        val chatId = runBlocking {
            withTimeout(TIMEOUT_MS) { peer.chatListFlow().first { list -> list.any { it.name == groupName } } }
                .first { it.name == groupName }.chatId
        }
        val peerMessages = peerMessages(peer, chatId)

        // App sends a text; the peer receives it.
        val appText = "Anchored off Hydra $suffix"
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(appText)
        compose.onNodeWithContentDescription(string(ChatR.string.composer_send)).performClick()
        val appMessage = peerMessages.await { !it.isOwn && (it.content as? MessageContent.Text)?.body == appText }

        // Peer replies; the app shows the reply with its quoted preview.
        val replyText = "Swim at noon $suffix"
        val replyId = runBlocking { peer.sendText(chatId, replyText, appMessage.messageId) }
        compose.waitForText(replyText)
        compose.waitFor(hasContentDescription(appText, substring = true))

        // App reacts to the peer message; the peer sees it.
        compose.onNode(hasText(replyText) and hasClickAction()).performTouchInput { longClick() }
        val react = string(ChatR.string.composer_react, REACTION)
        compose.waitFor(hasContentDescription(react))
        compose.onNodeWithContentDescription(react).performClick()
        peerMessages.await { it.messageId == replyId && it.reactions.any { r -> r.emoji == REACTION && r.count >= 1 } }

        // Peer sends an image; the app shows the image row.
        val peerPhoto = copyAsset(File(peerDir, "peer-photo.png"))
        runBlocking { peer.sendMedia(chatId, peerPhoto.path, PNG_MIME, null, null) }
        compose.waitFor(hasContentDescription(string(ChatR.string.chat_image)))

        // App sends an image through the stubbed photo picker; the peer receives it.
        val picked = copyAsset(File(pickedDir, "app-photo.png"))
        val pickedUri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.files", picked)
        Intents.init()
        try {
            intending(not(isInternal())).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(pickedUri)))
            compose.onNodeWithContentDescription(string(ChatR.string.composer_attach)).performClick()
            compose.waitForText(string(ChatR.string.composer_attach_photo))
            compose.onNodeWithText(string(ChatR.string.composer_attach_photo)).performClick()
            val appImage = peerMessages.await { !it.isOwn && it.content is MessageContent.Image }

            // Peer marks the app's latest message read; the app shows the read tick.
            runBlocking { peer.markRead(chatId, appImage.messageId) }
            compose.waitFor(hasContentDescription(string(DesignR.string.gim_tick_read)))
        } finally {
            Intents.release()
        }

        // Sign out through the chat list overflow menu.
        assertTrue(keystoreHasAlias())
        compose.onNodeWithContentDescription(string(ChatR.string.chat_back)).performClick()
        compose.waitForText(string(ChatListR.string.chatlist_title))
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_more_options)).performClick()
        compose.waitForText(string(ChatListR.string.chatlist_sign_out))
        compose.onNodeWithText(string(ChatListR.string.chatlist_sign_out)).performClick()
        compose.waitForText(string(ChatListR.string.chatlist_sign_out_title))
        // The dialog's confirm button, once the overflow menu item has gone.
        val signOut = hasText(string(ChatListR.string.chatlist_sign_out))
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(signOut).fetchSemanticsNodes().size == 1 }
        compose.onNode(signOut).performClick()
        compose.waitForText(string(OnboardingR.string.onboarding_title))
        assertFalse(keystoreHasAlias())
    }

    /** Messages of [chatId] as the peer sees them, kept current by one timeline subscription. */
    private fun peerMessages(peer: GimClient, chatId: String): StateFlow<ImmutableList<TimelineItem>> = runBlocking {
        val timeline = peer.timeline(chatId)
        timeline.itemsFlow().stateIn(peerScope, SharingStarted.Eagerly, persistentListOf())
    }

    private fun StateFlow<ImmutableList<TimelineItem>>.await(predicate: (MessageItem) -> Boolean): MessageItem =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                first { items -> items.messages().any(predicate) }.messages().first(predicate)
            }
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

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitFor(matcher: SemanticsMatcher) =
        waitUntil(TIMEOUT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForText(text: String) = waitFor(hasText(text))

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
        const val REACTION = "👍"
        const val PHOTO_ASSET = "e2e-photo.png"
        const val PNG_MIME = "image/png"

        // Tags of internal constants in the feature modules (OnboardingTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_HANDLE = "onboarding_handle"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_COMPOSER = "composer-input"
    }
}
