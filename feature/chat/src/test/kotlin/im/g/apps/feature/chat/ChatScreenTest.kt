package im.g.apps.feature.chat

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.FileUi
import im.g.apps.feature.chat.timeline.MessageRow
import im.g.apps.feature.chat.timeline.TimelineRowUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val loadOlderCalls = mutableListOf<Unit>()
    private val retried = mutableListOf<String>()
    private val retriedMedia = mutableListOf<String>()

    private fun message(id: String, body: String, tick: TickState? = null) = MessageRow(
        key = id,
        contentType = "message-text",
        outgoing = tick != null,
        senderName = null,
        body = body,
        image = null,
        file = null,
        replyPreview = null,
        reactions = persistentListOf(),
        edited = false,
        deleted = false,
        unsupported = false,
        tick = tick,
        time = "12:00",
        groupedWithPrevious = false,
        groupedWithNext = false,
    )

    private fun show(items: List<TimelineRowUi>, hasMoreOlder: Boolean = true) {
        val state = ChatUiState(
            header = ChatHeaderUi(title = "Aegean Crew", isGroup = true),
            items = items.toImmutableList(),
            hasMoreOlder = hasMoreOlder,
        )
        rule.setContent {
            GimTheme {
                ChatScreen(
                    state = state,
                    composer = { ComposerPlaceholder() },
                    onBack = {},
                    onOpenGroupInfo = {},
                    onLoadOlder = { loadOlderCalls += Unit },
                    onVisible = {},
                    onRetry = { retried += it },
                    onImageNeeded = {},
                    onOpenFile = {},
                    onMessageLongPress = {},
                    onRetryMedia = { retriedMedia += it },
                )
            }
        }
    }

    @Test
    fun newestMessageIsAtTheBottom() {
        show(listOf(message("m3", "newest"), message("m2", "middle"), message("m1", "oldest")))

        val newest = rule.onNodeWithText("newest").getUnclippedBoundsInRoot()
        val middle = rule.onNodeWithText("middle").getUnclippedBoundsInRoot()
        val oldest = rule.onNodeWithText("oldest").getUnclippedBoundsInRoot()
        assertTrue(newest.top > middle.top)
        assertTrue(middle.top > oldest.top)
    }

    @Test
    fun scrollingToTheOldestRowsLoadsOlder() {
        val items = (100 downTo 1).map { message("m$it", "Message $it") }
        show(items)
        rule.waitForIdle()
        assertEquals(0, loadOlderCalls.size)

        rule.onNodeWithTag(TIMELINE_TEST_TAG).performScrollToIndex(items.lastIndex)
        rule.waitForIdle()

        assertEquals(1, loadOlderCalls.size)
    }

    @Test
    fun noLoadOlderAtTheStartOfHistory() {
        val items = (100 downTo 1).map { message("m$it", "Message $it") }
        show(items, hasMoreOlder = false)

        rule.onNodeWithTag(TIMELINE_TEST_TAG).performScrollToIndex(items.lastIndex)
        rule.waitForIdle()

        assertEquals(0, loadOlderCalls.size)
    }

    @Test
    fun tappingFailedMessageRetries() {
        show(listOf(message("m2", "Lost", TickState.FAILED), message("m1", "Fine", TickState.READ)))

        rule.onNodeWithText("Not sent. Tap to try again").assertExists()
        rule.onNodeWithText("Fine").performClick()
        assertEquals(emptyList<String>(), retried)

        rule.onNodeWithText("Lost").performClick()
        assertEquals(listOf("m2"), retried)
    }

    @Test
    fun failedMessageAfterSuspension_offersNoRetry() {
        val lost = message("m2", "Lost", TickState.FAILED).copy(sendFailure = SendFailure.USER_SUSPENDED)
        show(listOf(lost, message("m1", "Fine", TickState.READ)))

        rule.onNodeWithText("Not sent").assertExists()
        rule.onNodeWithText("Not sent. Tap to try again").assertDoesNotExist()
        rule.onNodeWithText("Lost").performClick()
        assertEquals(emptyList<String>(), retried)
    }

    @Test
    fun failedMessageNotAdmittedByPeer_showsTheChatHubText_noRetry() {
        val refused = message("m2", "Refused", TickState.FAILED).copy(sendFailure = SendFailure.NOT_ADMITTED_BY_PEER)
        show(listOf(refused, message("m1", "Fine", TickState.READ)))

        rule.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(DesignR.string.gim_error_not_admitted_by_peer)).assertExists()
        rule.onNodeWithText("Not sent. Tap to try again").assertDoesNotExist()
        rule.onNodeWithText("Refused").performClick()
        assertEquals(emptyList<String>(), retried)
    }

    @Test
    fun failedMessageRejected_keepsTapToRetry() {
        show(listOf(message("m2", "Lost", TickState.FAILED).copy(sendFailure = SendFailure.REJECTED)))

        rule.onNodeWithText("Not sent. Tap to try again").assertExists()
        rule.onNodeWithText("Lost").performClick()
        assertEquals(listOf("m2"), retried)
    }

    @Test
    fun unavailableMedia_showsARetryButton() {
        val file = message("m2", "Route").copy(
            contentType = "message-file",
            file = FileUi("route.pdf", "12 KB", "application/pdf", "PDF"),
            mediaUnavailable = true,
        )
        show(listOf(file, message("m1", "Fine")))

        rule.onNodeWithText("Download again", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(MEDIA_RETRY_TEST_TAG).performClick()
        assertEquals(listOf("m2"), retriedMedia)
    }
}
