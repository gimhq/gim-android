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
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.skipIntro
import im.g.apps.testing.openSettingsRow
import java.io.File
import java.net.URI
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.settings.R as SettingsR
import im.g.apps.feature.hubs.R as HubsR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * Hub management against two federated gim-hubs (instrumentation arguments `gimServerUrl` = hub A and
 * `gimServerUrl2` = hub B; `GIM_HUB2=1 scripts/run-device-tests.sh`, skipped without hub B): the app onboards on A,
 * adds B from the Hubs screen, makes it primary and keeps messaging with an in-process peer on A, creates a group
 * (owned by its primary hub B) inviting that peer, homed on A only, and messages in it both ways (C4-T9), is refused
 * removing its home hub A, then removes B after confirming the group chats B owns and still messages through A.
 *
 * A second B-owned group is created by a second in-process peer whose home hub is B, with the app user as member.
 */
@RunWith(AndroidJUnit4::class)
class HubsE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL
    private val serverUrl2: String? = args.getString("gimServerUrl2")

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val peerName = "Peer A $suffix"
    private val groupName = "Owned by B $suffix"
    private val ownGroupName = "App group on B $suffix"
    private val peerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peerDir = File(instrumentation.targetContext.cacheDir, "e2e-hubs-peer-$suffix")
    private val peerBDir = File(instrumentation.targetContext.cacheDir, "e2e-hubs-peer-b-$suffix")
    private val peers = mutableListOf<GimClient>()

    @After
    fun tearDown() {
        if (serverUrl2 == null) return
        peerScope.cancel()
        runBlocking {
            for (peer in peers) {
                try {
                    peer.signOut()
                } catch (_: GimException) {
                    // Local wipe below still runs.
                }
            }
            // Leave the app signed out for the next test class.
            (instrumentation.targetContext.applicationContext as GimApplication).container.signOut()
        }
        peerDir.deleteRecursively()
        peerBDir.deleteRecursively()
    }

    @Test
    fun addsSecondHubMakesItPrimaryAndRemovesIt() {
        assumeTrue("no gimServerUrl2 (hub B); run with GIM_HUB2=1", serverUrl2 != null)
        val urlB = checkNotNull(serverUrl2)
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        awaitServerReachable(instrumentation.targetContext, urlB)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // 1. Onboarding on hub A: skip the intro, create identity, pass the recovery kit check, skip the handle ->
        // chat list.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Hubs $suffix")
        click("create account", TAG_SUBMIT)
        compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitFor("handle step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip handle", TAG_SKIP_HANDLE)
        waitFor("chat list", chatList())
        val app = appClient()
        val appUserId = checkNotNull(app.userId())
        step("onboarded on hub A")

        // 2. Hubs screen: A is Home, Primary, Verified and signed in.
        openHubs()
        val hubA = bounded("app lists hub A") { app.hubs().single() }
        assertTrue("hub A is not the home hub: $hubA", hubA.isHome && hubA.isPrimary && hubA.signedIn)
        waitFor("hub A row", inRow(hubA.hubId, hasText(host(serverUrl))))
        waitFor("hub A home badge", inRow(hubA.hubId, hasText(string(HubsR.string.hubs_badge_home))))
        waitFor("hub A primary badge", inRow(hubA.hubId, hasText(string(HubsR.string.hubs_badge_primary))))
        waitFor("hub A verified and signed in", inRow(hubA.hubId, hasText(verifiedSignedIn())))
        step("Hubs screen shows A: Home, Primary, Verified, Signed in")

        // 3. Add hub B through the dialog: listed Verified and signed in.
        compose.onNodeWithContentDescription(string(HubsR.string.hubs_add)).performClick()
        waitFor("add hub dialog", hasTestTag(TAG_ADD_URL))
        enterText("hub B address", TAG_ADD_URL, urlB)
        click("add hub B", TAG_ADD_CONFIRM)
        waitFor("add hub dialog closed") { !exists(hasTestTag(TAG_ADD_URL)) }
        val hubB = bounded("app lists hub B") {
            app.hubs().first { it.hubId != hubA.hubId }
        }
        waitFor("hub B row", inRow(hubB.hubId, hasText(host(urlB))))
        waitFor("hub B verified and signed in", inRow(hubB.hubId, hasText(verifiedSignedIn())))
        saveFailureScreenshot(instrumentation, "$LOG_TAG-hubs-screen-two-hubs")
        step("added hub B (${hubB.hubId}): Verified, Signed in")

        // 4. Make B primary.
        click("make B primary", inRow(hubB.hubId, hasText(string(HubsR.string.hubs_make_primary)) and hasClickAction()))
        waitFor("hub B is primary", inRow(hubB.hubId, hasText(string(HubsR.string.hubs_badge_primary))))
        waitFor("hub A no longer primary") {
            !exists(inRow(hubA.hubId, hasText(string(HubsR.string.hubs_badge_primary))))
        }
        assertTrue(bounded("bridge reports B primary") { app.hubs().first().let { it.hubId == hubB.hubId && it.isPrimary } })
        step("hub B is primary")

        // 5. Messaging with B primary: a peer on hub A sends a direct message, the app replies from the UI.
        val peer = newPeer(serverUrl, peerDir, peerName)
        exchangeDirectMessages(peer, appUserId, "Fair winds via B $suffix", "Reply via B $suffix")
        step("messaging works with B primary")

        // 5b. With B primary the app creates a group on B inviting the peer homed only on A; messages flow both ways.
        val peerUserId = checkNotNull(peer.userId())
        val ownGroupId = bounded("app creates a group inviting the peer on A") {
            app.createGroupChat(ownGroupName, listOf(peerUserId))
        }
        Log.i(LOG_TAG, "group $ownGroupId created by the app with B primary")
        assertTrue("group $ownGroupId is not owned by ${hubB.hubId}", ownGroupId.startsWith(hubB.hubId + "/"))
        bounded("peer on A joins the app's group") { peer.chatListFlow().first { list -> list.any { it.chatId == ownGroupId } } }
        exchangeGroupMessages(peer, ownGroupId, ownGroupName, "Ahoy from A $suffix", "Group reply via B $suffix")
        step("group created with B primary includes the peer homed on A; messaging works both ways")

        // 6. Removing home hub A is refused with an explanation.
        openHubs()
        click("remove A", inRow(hubA.hubId, hasText(string(HubsR.string.hubs_remove)) and hasClickAction()))
        waitFor("home hub explanation", hasText(string(HubsR.string.hubs_error_home_hub)))
        compose.onNodeWithText(string(HubsR.string.hubs_ok)).performClick()
        waitFor("explanation closed") { !exists(hasText(string(HubsR.string.hubs_error_home_hub))) }
        step("removing A shows the home hub explanation")

        // 7. A group owned by hub B (created by a peer whose home hub is B), then remove B after confirming.
        val peerB = newPeer(urlB, peerBDir, "Peer B $suffix")
        val groupId = bounded("peer on B creates the group") { peerB.createGroupChat(groupName, listOf(appUserId)) }
        Log.i(LOG_TAG, "group $groupId created on hub B")
        assertTrue("group $groupId is not owned by ${hubB.hubId}", groupId.startsWith(hubB.hubId + "/"))
        bounded("app joins the B-owned group") { app.chatListFlow().first { list -> list.any { it.chatId == groupId } } }
        step("app joined group owned by B")

        removeHubConfirmingOwnedGroups(hubB.hubId, ownedGroups = 2)
        waitFor("hub B shown removed", inRow(hubB.hubId, hasText(removedNotSignedIn())))
        waitFor("hub A primary again", inRow(hubA.hubId, hasText(string(HubsR.string.hubs_badge_primary))))
        step("hub B removed after the owned-groups confirmation")

        // 8. Messaging still works through A.
        exchangeDirectMessages(peer, appUserId, "Still afloat via A $suffix", "Reply via A $suffix")
        step("messaging works via A after removing B")
    }

    /**
     * Removes [hubId] from its row and accepts the owned-groups warning ("This hub runs [ownedGroups] of your group
     * chats"). A [GimException.HubNotDrained] answer (something sent is not acknowledged yet) is acknowledged and
     * retried.
     */
    private fun removeHubConfirmingOwnedGroups(hubId: String, ownedGroups: Int) {
        val owned = hasText(string(HubsR.string.hubs_owned_body, ownedGroups))
        val notDrained = hasText(string(HubsR.string.hubs_error_not_drained))
        repeat(DRAIN_ATTEMPTS) { attempt ->
            click("remove B", inRow(hubId, hasText(string(HubsR.string.hubs_remove)) and hasClickAction()))
            waitFor("owned groups warning or not drained") { exists(owned) || exists(notDrained) }
            if (exists(owned)) {
                compose.onNodeWithText(string(HubsR.string.hubs_owned_confirm)).performClick()
                waitFor("removal done or not drained") {
                    exists(inRow(hubId, hasText(removedNotSignedIn()))) || exists(notDrained)
                }
                if (!exists(notDrained)) return
            }
            Log.w(LOG_TAG, "hub $hubId not drained (attempt ${attempt + 1}); retrying")
            compose.onNodeWithText(string(HubsR.string.hubs_ok)).performClick()
            Thread.sleep(DRAIN_RETRY_MS)
        }
        failStep("hub $hubId still not drained after $DRAIN_ATTEMPTS attempts", AssertionError("not drained"))
    }

    /** The peer sends [text] in its direct chat with the app user; the app shows it and answers [reply] from the UI. */
    private fun exchangeDirectMessages(peer: GimClient, appUserId: String, text: String, reply: String) {
        val chatId = bounded("peer opens the direct chat") { peer.createDirectChat(appUserId) }
        val peerMessages = messages(peer, chatId)
        bounded("peer sends '$text'") { peer.sendText(chatId, text, null) }
        backToChatList()
        click("open the direct chat", hasText(peerName, substring = true) and hasClickAction())
        waitFor("app shows '$text'", hasText(text))
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(reply)
        click("send reply", hasContentDescription(string(ChatR.string.composer_send)))
        peerMessages.await("peer receives '$reply'") {
            !it.isOwn && (it.content as? MessageContent.Text)?.body == reply
        }
    }

    /** The peer sends [text] in group [chatId]; the app opens [name], shows it and answers [reply] from the UI. */
    private fun exchangeGroupMessages(peer: GimClient, chatId: String, name: String, text: String, reply: String) {
        val peerMessages = messages(peer, chatId)
        bounded("peer sends '$text' in the group") { peer.sendText(chatId, text, null) }
        backToChatList()
        click("open the group", hasText(name) and hasClickAction())
        waitFor("app shows '$text' in the group", hasText(text))
        compose.onNodeWithTag(TAG_COMPOSER).performTextInput(reply)
        click("send group reply", hasContentDescription(string(ChatR.string.composer_send)))
        peerMessages.await("peer receives '$reply' in the group") {
            !it.isOwn && (it.content as? MessageContent.Text)?.body == reply
        }
    }

    private fun newPeer(url: String, dir: File, name: String): GimClient = bounded("peer on $url signs up") {
        val peer = UniffiGimClientFactory().create(ClientConfig(url), InMemoryHooks(dir)).also { peers += it }
        peer.createIdentity(name)
        peer.startSync()
        peer
    }

    private fun messages(client: GimClient, chatId: String): StateFlow<ImmutableList<TimelineItem>> = runBlocking {
        client.timeline(chatId).itemsFlow().stateIn(peerScope, SharingStarted.Eagerly, persistentListOf())
    }

    private fun StateFlow<ImmutableList<TimelineItem>>.await(step: String, predicate: (MessageItem) -> Boolean) {
        bounded(step) { first { items -> items.filterIsInstance<TimelineItem.Message>().any { predicate(it.item) } } }
    }

    /** From any screen of this test back to the chat list. */
    private fun backToChatList() {
        while (!exists(chatList())) {
            val back = listOf(string(HubsR.string.hubs_back), string(SettingsR.string.settings_back), string(ChatR.string.chat_back))
                .map { hasContentDescription(it) and hasClickAction() }
                .firstOrNull { exists(it) } ?: break
            compose.onNode(back).performClick()
            compose.waitForIdle()
        }
        waitFor("chat list", chatList())
    }

    private fun openHubs() {
        backToChatList()
        compose.openSettingsRow(LOG_TAG, string(SettingsR.string.settings_servers), timeoutMs, ::failStep)
        waitFor("hubs screen", hasText(string(HubsR.string.hubs_title)))
    }

    private fun chatList() = hasText(string(ChatListR.string.chatlist_title))

    private fun inRow(hubId: String, matcher: SemanticsMatcher) =
        matcher and hasAnyAncestor(hasTestTag(TAG_ROW_PREFIX + hubId))

    private fun verifiedSignedIn() =
        string(HubsR.string.hubs_state_verified) + " · " + string(HubsR.string.hubs_signed_in)

    private fun removedNotSignedIn() =
        string(HubsR.string.hubs_state_removed) + " · " + string(HubsR.string.hubs_not_signed_in)

    private fun host(url: String): String = URI(url).let { if (it.port > 0) "${it.host}:${it.port}" else it.host }

    private fun appClient(): GimClient =
        checkNotNull((compose.activity.application as GimApplication).container.manager.client.value)

    private fun step(name: String) = Log.i(LOG_TAG, "STEP OK: $name")

    /** Runs [block] bounded by twice [timeoutMs]; a timeout fails the test with [step]. */
    private fun <T> bounded(step: String, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs * 2) { block() } }
    } catch (e: TimeoutCancellationException) {
        failStep(step, e)
    }

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String = compose.activity.getString(id, *formatArgs)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitFor(step) { exists(matcher) }

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
    private fun click(button: String, target: SemanticsMatcher) {
        waitFor(button, target)
        compose.clickWhenSettled(LOG_TAG, button, target, timeoutMs)
    }

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
        throw AssertionError("step '$step' failed after up to $timeoutMs ms", cause)
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
        const val LOG_TAG = "HubsE2eTest"
        const val DRAIN_ATTEMPTS = 5
        const val DRAIN_RETRY_MS = 2_000L

        // Tags of internal constants in the feature modules (OnboardingTestTags, HubsTestTags, COMPOSER_INPUT_TEST_TAG).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_ROW_PREFIX = "hubs_row_"
        const val TAG_ADD_URL = "hubs_add_url"
        const val TAG_ADD_CONFIRM = "hubs_add_confirm"
        const val TAG_COMPOSER = "composer-input"
    }
}
