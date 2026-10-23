package im.g.apps.feature.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.chat.timeline.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class ComposerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = MessageActionsUi(
        messageId = "m1",
        canReply = true,
        copyText = "Anchored off Paxos",
        canEdit = false,
        canDelete = false,
        canReact = true,
        canRetry = false,
    )

    /** Composer plus action sheet content over local state, wired like [ComposerRoute] wires the ViewModel. */
    private fun show(onSend: () -> Unit = {}) {
        rule.setContent {
            GimTheme {
                var state by remember { mutableStateOf(ComposerUiState(actions = actions)) }
                Column {
                    state.actions?.let {
                        MessageActionsContent(
                            actions = it,
                            quickReactions = state.quickReactions,
                            callbacks = MessageActionCallbacks(
                                onReply = { id ->
                                    state = state.copy(
                                        actions = null,
                                        mode = ComposerMode.Replying(id, "Nikos", UiText.Raw("Anchored off Paxos")),
                                    )
                                },
                                onCopy = {},
                                onEdit = {},
                                onDelete = {},
                                onReact = { _, _ -> },
                                onRetry = {},
                                onCancelSend = {},
                            ),
                        )
                    }
                    Composer(
                        state = state,
                        onDraftChange = { state = state.copy(draft = it) },
                        onSend = onSend,
                        onCancelMode = { state = state.copy(mode = ComposerMode.Normal) },
                        onAttachPhoto = {},
                        onAttachFile = {},
                    )
                }
            }
        }
    }

    @Test
    fun sendButtonEnabledOnlyForNonBlankDraft() {
        var sends = 0
        show(onSend = { sends++ })
        val send = rule.onNodeWithContentDescription("Send message")

        send.assertIsNotEnabled()
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).performTextInput("   ")
        send.assertIsNotEnabled()
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).performTextReplacement("Hello")
        send.assertIsEnabled()

        send.performClick()
        assertEquals(1, sends)
    }

    @Test
    fun replyBarAppearsAfterReplyAndDisappearsOnClose() {
        show()
        rule.onNodeWithText("Nikos").assertDoesNotExist()

        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithText("Nikos").assertExists()
        rule.onNodeWithText("Reply").assertDoesNotExist()

        rule.onNodeWithContentDescription("Cancel reply").performClick()
        rule.onNodeWithText("Nikos").assertDoesNotExist()
    }

    @Test
    fun leavingAndLeft_disableTheInputWithTheirLabel() {
        var membership by mutableStateOf(ChatMembership.LEAVING)
        rule.setContent {
            GimTheme {
                Composer(
                    state = ComposerUiState(membership = membership),
                    onDraftChange = {},
                    onSend = {},
                    onCancelMode = {},
                    onAttachPhoto = {},
                    onAttachFile = {},
                )
            }
        }
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).assertIsNotEnabled()
        rule.onNodeWithText("Leaving…", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Attach a photo or file").assertIsNotEnabled()

        membership = ChatMembership.LEFT
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).assertIsNotEnabled()
        rule.onNodeWithText("You left this group", useUnmergedTree = true).assertExists()

        membership = ChatMembership.REMOVED
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).assertIsNotEnabled()
        rule.onNodeWithText("You were removed from this group", useUnmergedTree = true).assertExists()

        membership = ChatMembership.JOINED
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).assertIsEnabled()
        rule.onNodeWithTag(COMPOSER_BANNER_TEST_TAG).assertDoesNotExist()
    }

    /** Bare composer in [mode] with [draft]; the whole bar carries [ROOT_TAG]. */
    private fun showBare(mode: ComposerMode = ComposerMode.Normal, draft: String = "") {
        var state by mutableStateOf(ComposerUiState(mode = mode, draft = draft))
        rule.setContent {
            GimTheme {
                Composer(
                    state = state,
                    onDraftChange = { state = state.copy(draft = it) },
                    onSend = {},
                    onCancelMode = {},
                    onAttachPhoto = {},
                    onAttachFile = {},
                    modifier = Modifier.testTag(ROOT_TAG),
                )
            }
        }
    }

    private fun attach() = rule.onNodeWithContentDescription(ATTACH)

    @Test
    fun attachAndSendStayInEveryModeWith48dpTargets() {
        var mode by mutableStateOf<ComposerMode>(ComposerMode.Normal)
        rule.setContent {
            GimTheme {
                Composer(
                    state = ComposerUiState(mode = mode),
                    onDraftChange = {},
                    onSend = {},
                    onCancelMode = {},
                    onAttachPhoto = {},
                    onAttachFile = {},
                )
            }
        }
        fun assertButtons(sendLabel: String, attachEnabled: Boolean) {
            val send = rule.onNodeWithContentDescription(sendLabel)
            send.assertIsDisplayed().assertIsNotEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            attach().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            if (attachEnabled) attach().assertIsEnabled() else attach().assertIsNotEnabled()
        }

        assertButtons("Send message", attachEnabled = true)
        mode = ComposerMode.Replying("m1", "Nikos", UiText.Raw("Anchored off Paxos"))
        assertButtons("Send message", attachEnabled = true)
        mode = ComposerMode.Editing("m1", "Anchored off Paxos")
        assertButtons("Save changes", attachEnabled = false)
    }

    @Test
    fun multiLineDraftKeepsButtonsAtTheBottom() {
        showBare()
        fun gaps(): Pair<Float, Float> {
            val bottom = rule.onNodeWithTag(ROOT_TAG).getBoundsInRoot().bottom
            val send = rule.onNodeWithContentDescription("Send message").getBoundsInRoot().bottom
            val attach = attach().getBoundsInRoot().bottom
            return (bottom - send).value to (bottom - attach).value
        }
        val inputBefore = rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).getBoundsInRoot().height
        val before = gaps()

        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).performTextReplacement("one\ntwo\nthree\nfour")

        assertTrue(rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).getBoundsInRoot().height > inputBefore)
        val after = gaps()
        assertEquals(before.first, after.first, 0.5f)
        assertEquals(before.second, after.second, 0.5f)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun largeFontKeepsButtonsVisibleAndPlaceholderUnclipped() {
        showBare()
        rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG).assertIsDisplayed()
        attach().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        val placeholder = rule.onAllNodesWithText("Message", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val layouts = mutableListOf<TextLayoutResult>()
        placeholder.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    fun traversalOrderIsAttachInputSend() {
        showBare(draft = "Hi")
        val order = rule.onAllNodes(
            hasContentDescription(ATTACH) or hasTestTag(COMPOSER_INPUT_TEST_TAG) or
                hasContentDescription("Send message"),
        ).fetchSemanticsNodes().map { node ->
            when {
                node.config.contains(SemanticsProperties.TestTag) -> "input"
                node.config[SemanticsProperties.ContentDescription].single() == ATTACH ->
                    "attach"
                else -> "send"
            }
        }
        assertEquals(listOf("attach", "input", "send"), order)
    }

    private companion object {
        const val ROOT_TAG = "composer-root"
        const val ATTACH = "Attach a photo or file"
    }
}
