package im.g.apps.navigation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.testContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/** The chat destination of [GimNavHost] passes the composer's reply and reaction toggles to the chat screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
// Taps on reaction chips are hit-tested against the bubble's clip path, which only native graphics evaluates.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatReplyReactionWiringTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(Job())
    private val fake = FakeGimClient.withDemoData(scope, outboxDelays = null)
    private val client = RecordingClient(fake)

    @Test
    fun reply_action_and_reaction_chips_reach_the_composer_and_the_client() {
        val messageId = messageIdOf(DemoData.DIRECT_CHAT_ID, TARGET)
        val container = testContainer(scope = scope, factory = GimClientFactory { _, _ -> client })
        val start = runBlocking { resolveStartDestination(container.manager) }
        rule.setContent { GimTheme { GimNavHost(container, start) } }

        rule.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        rule.onNodeWithText(DemoData.lukas.displayName).performClick()
        rule.waitUntilAtLeastOneExists(hasText("See you then!"), TIMEOUT)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(TARGET))

        // TalkBack "Reply" on the message opens the composer's reply bar for it.
        val actions = rule.onNodeWithText(TARGET).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.single { it.label == "Reply" }.action() }
        rule.waitUntilAtLeastOneExists(
            hasContentDescription("Replying to", substring = true) and hasContentDescription(TARGET, substring = true),
            TIMEOUT,
        )

        // Lukas reacted 👍: tapping the chip adds the own reaction, tapping it again removes it.
        rule.onNodeWithContentDescription("👍, 1 reaction").performClick()
        rule.waitUntil(TIMEOUT) { client.calls.size == 1 }
        rule.waitUntilAtLeastOneExists(hasContentDescription("👍, 2 reactions, including yours"), TIMEOUT)
        rule.onNodeWithContentDescription("👍, 2 reactions, including yours").performClick()
        rule.waitUntil(TIMEOUT) { client.calls.size == 2 }

        assertEquals(
            listOf(
                Call("react", DemoData.DIRECT_CHAT_ID, messageId, "👍"),
                Call("unreact", DemoData.DIRECT_CHAT_ID, messageId, "👍"),
            ),
            client.calls.toList(),
        )
    }

    private fun messageIdOf(chatId: String, body: String): String = runBlocking {
        val loaded = CompletableDeferred<List<TimelineItem>>()
        val handle = fake.timeline(chatId).addListener { diffs ->
            diffs.filterIsInstance<TimelineDiff.Reset>().firstOrNull()?.let { loaded.complete(it.values) }
        }
        try {
            withTimeout(TIMEOUT) { loaded.await() }
                .filterIsInstance<TimelineItem.Message>()
                .single { (it.item.content as? MessageContent.Text)?.body == body }
                .item.messageId
        } finally {
            handle.cancel()
        }
    }

    private data class Call(val kind: String, val chatId: String, val messageId: String, val emoji: String)

    private class RecordingClient(private val inner: GimClient) : GimClient by inner {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

        override suspend fun react(chatId: String, messageId: String, emoji: String) {
            calls += Call("react", chatId, messageId, emoji)
            inner.react(chatId, messageId, emoji)
        }

        override suspend fun unreact(chatId: String, messageId: String, emoji: String) {
            calls += Call("unreact", chatId, messageId, emoji)
            inner.unreact(chatId, messageId, emoji)
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val TARGET = "Great, he knows the Ionian well."
    }
}
