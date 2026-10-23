package im.g.apps.feature.chat.composer

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageActionSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val replied = mutableListOf<String>()
    private val reacted = mutableListOf<Pair<String, String>>()

    private fun show() {
        val actions = MessageActionsUi(
            messageId = "m1",
            canReply = true,
            copyText = "Anchored off Paxos",
            canEdit = true,
            canDelete = true,
            canReact = true,
            ownReactions = persistentSetOf("👍"),
            canRetry = false,
        )
        rule.setContent {
            GimTheme {
                MessageActionsContent(
                    actions = actions,
                    quickReactions = QUICK_REACTIONS,
                    callbacks = MessageActionCallbacks(
                        onReply = { replied += it },
                        onCopy = {},
                        onEdit = {},
                        onDelete = {},
                        onReact = { id, emoji -> reacted += id to emoji },
                        onRetry = {},
                        onCancelSend = {},
                    ),
                )
            }
        }
    }

    @Test
    fun reactionRowComesFirst_thenReply() {
        show()
        val reaction = rule.onNodeWithContentDescription("React with 👍").getBoundsInRoot()
        val reply = rule.onNodeWithText("Reply").getBoundsInRoot()
        val copy = rule.onNodeWithText("Copy").getBoundsInRoot()
        assertTrue("reactions $reaction not above reply $reply", reaction.bottom <= reply.top)
        assertTrue("reply $reply not above copy $copy", reply.bottom <= copy.top)

        rule.onNodeWithText("Reply").performClick()
        assertEquals(listOf("m1"), replied)
    }

    @Test
    fun reactionButtons_are48dp_andOwnReactionIsSelected() {
        show()
        QUICK_REACTIONS.forEach { emoji ->
            rule.onNodeWithContentDescription("React with $emoji")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithContentDescription("React with 👍").assertIsSelected()
        rule.onNodeWithContentDescription("React with ❤️").assertIsNotSelected().performClick()
        assertEquals(listOf("m1" to "❤️"), reacted)
    }

    @Test
    fun actionRows_are48dpHigh() {
        show()
        listOf("Reply", "Copy", "Edit", "Delete").forEach {
            rule.onNodeWithText(it).assertHeightIsAtLeast(48.dp)
        }
    }
}
