package im.g.apps.feature.chat

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.ReactionChipModel
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.DateSeparatorRow
import im.g.apps.feature.chat.timeline.FileUi
import im.g.apps.feature.chat.timeline.MessageRow
import im.g.apps.feature.chat.timeline.SenderUi
import im.g.apps.feature.chat.timeline.TimelineRowUi
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val loadOlderCalls = mutableListOf<Unit>()
    private val retried = mutableListOf<String>()
    private val retriedMedia = mutableListOf<String>()
    private val longPressed = mutableListOf<String>()
    private val replied = mutableListOf<String>()
    private val toggled = mutableListOf<Pair<String, String>>()
    private var backs = 0

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
                    onBack = { backs++ },
                    onOpenGroupInfo = {},
                    onLoadOlder = { loadOlderCalls += Unit },
                    onVisible = {},
                    onRetry = { retried += it },
                    onImageNeeded = {},
                    onOpenFile = {},
                    onMessageLongPress = { longPressed += it },
                    onRetryMedia = { retriedMedia += it },
                    onReply = { replied += it },
                    onToggleReaction = { id, emoji -> toggled += id to emoji },
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

    @Test
    fun messageNode_offersOptionsOnLongPress_andTalkBackActions() {
        show(listOf(message("m2", "Lost", TickState.FAILED), message("m1", "Hello")))

        val hello = rule.onNodeWithText("Hello").fetchSemanticsNode()
        assertEquals("Show options", hello.config[SemanticsActions.OnLongClick].label)
        val actions = hello.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Reply", "React"), actions.map { it.label })
        actions.single { it.label == "Reply" }.action()
        actions.single { it.label == "React" }.action()
        assertEquals(listOf("m1"), replied)
        assertEquals(listOf("m1"), longPressed)

        val lost = rule.onNodeWithText("Lost").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Reply", "React", "Try again"), lost.map { it.label })
        lost.single { it.label == "Try again" }.action()
        assertEquals(listOf("m2"), retried)
    }

    @Test
    fun messageNode_readsSenderBodyTimeEditedAndState() {
        val inbound = message("m2", "Wind looks good").copy(
            senderName = "Nikos",
            sender = SenderUi("u-nikos", "Nikos"),
            edited = true,
        )
        show(listOf(inbound, message("m1", "Perfect", TickState.READ)))

        assertEquals(
            listOf("Nikos", "Wind looks good", "12:00", "edited"),
            rule.onNodeWithText("Wind looks good").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertEquals(
            listOf("Perfect", "12:00", "Read"),
            rule.onNodeWithText("Perfect").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    // Taps inside the bubble are hit-tested against its clip path, which only native graphics evaluates.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reactionChipClick_togglesTheOwnReaction() {
        val reacted = message("m1", "Hello").copy(
            reactions = persistentListOf(ReactionChipModel("👍", 2, mine = true), ReactionChipModel("⚓", 1, mine = false)),
        )
        show(listOf(reacted))

        val mine = rule.onNodeWithContentDescription("👍, 2 reactions, including yours")
        val touch = mine.fetchSemanticsNode().touchBoundsInRoot
        val minPx = with(rule.density) { 48.dp.toPx() } - 1f
        assertTrue("chip touch area $touch", touch.height >= minPx && touch.width >= minPx)
        mine.performClick()
        rule.onNodeWithContentDescription("⚓, 1 reaction").performClick()
        assertEquals(listOf("m1" to "👍", "m1" to "⚓"), toggled)
    }

    @Test
    fun topBarBack_isLabelledAndLargeEnough() {
        show(listOf(message("m1", "Hello")))

        rule.onNodeWithContentDescription("Go back")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, backs)
        rule.onNodeWithText("Aegean Crew").assertIsDisplayed()
        rule.onNodeWithContentDescription("Group info").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun groupRun_showsTheAvatarOnlyBesideTheLastBubble() {
        val sender = SenderUi("u-nikos", "Nikos Papas")
        // Newest first: m2 is the last bubble of the run.
        show(
            listOf(
                message("m2", "second").copy(sender = sender, groupedWithPrevious = true),
                message("m1", "first").copy(sender = sender, senderName = "Nikos Papas", groupedWithNext = true),
            ),
        )

        rule.onAllNodesWithTag(SENDER_AVATAR_TEST_TAG).assertCountEquals(1)
        val avatar = rule.onNodeWithTag(SENDER_AVATAR_TEST_TAG).getBoundsInRoot()
        val second = rule.onNodeWithText("second", useUnmergedTree = true).getBoundsInRoot()
        val first = rule.onNodeWithText("first", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("avatar $avatar not beside the last bubble $second", avatar.top >= first.bottom && avatar.bottom > second.top)
        assertTrue("avatar $avatar overlaps the bubble text $second", avatar.right <= second.left)
        // Both bubbles start at the same x: the first one keeps a spacer where the avatar would be.
        assertEquals(first.left.value, second.left.value, 0.5f)
    }

    @Test
    @Config(fontScale = 2.0f)
    // Real text measurement; the legacy Robolectric graphics mode reports fake text widths.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun atDoubleFontScale_bodyTimeAndDateChipFit() {
        val body = "Wind looks good for Hvar tomorrow morning"
        show(listOf(message("m1", body, TickState.READ), DateSeparatorRow("date-1", UiText.Raw("Today"))))

        rule.onNodeWithText(body).assertIsDisplayed()
        rule.onNodeWithText("12:00", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()

        val layout = textLayout(body)
        assertFalse("body clipped vertically: ${layout.size}", layout.didOverflowHeight)
        (0 until layout.lineCount).forEach { assertFalse("body line $it ellipsized", layout.isLineEllipsized(it)) }
        assertFalse("time clipped", textLayout("12:00").didOverflowHeight)
        assertFalse("time ellipsized", textLayout("12:00").isLineEllipsized(0))

        val bubble = rule.onNodeWithText(body).getBoundsInRoot()
        val bodyBounds = rule.onNodeWithText(body, useUnmergedTree = true).getBoundsInRoot()
        val time = rule.onNodeWithText("12:00", useUnmergedTree = true).getBoundsInRoot()
        // The footer sits under the body instead of overlapping it, inside the bubble.
        assertTrue("time $time overlaps body $bodyBounds", time.top >= bodyBounds.bottom)
        assertTrue("time $time outside bubble $bubble", time.bottom <= bubble.bottom && time.right <= bubble.right)

        val chip = rule.onNode(isHeading()).getBoundsInRoot()
        val chipText = rule.onNodeWithText("Today", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("chip text $chipText outside chip $chip", chipText.top >= chip.top && chipText.bottom <= chip.bottom)
        assertFalse("chip label clipped", textLayout("Today").didOverflowHeight)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single()
    }
}
