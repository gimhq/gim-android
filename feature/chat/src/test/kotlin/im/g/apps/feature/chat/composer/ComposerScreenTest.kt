package im.g.apps.feature.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        val send = rule.onNodeWithContentDescription("Send")

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
}
