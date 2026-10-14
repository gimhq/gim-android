package im.g.apps.core.designsystem.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun avatar_withoutImage_showsInitials() {
        rule.setContent {
            GimTheme { Avatar(name = "Ada Lovelace", imagePath = null, size = 48.dp) }
        }
        rule.onNodeWithText("AL").assertExists()
    }

    @Test
    fun avatarInitials_handlesSingleWordBlankAndSurrogates() {
        assertEquals("G", avatarInitials("grace"))
        assertEquals("AB", avatarInitials("  anna  maria  bauer "))
        assertEquals("?", avatarInitials("   "))
        assertEquals("😀S", avatarInitials("😀 smile"))
    }

    @Test
    fun avatarColor_isDeterministic() {
        assertEquals(avatarColor("Ada Lovelace"), avatarColor("Ada Lovelace"))
        val distinct = listOf("Ada", "Grace", "Linus", "Marta", "Nikos", "Sofia").map(::avatarColor).toSet()
        assertNotEquals(1, distinct.size)
    }

    @Test
    fun unreadBadge_capsAt99Plus() {
        rule.setContent { GimTheme { UnreadBadge(count = 150) } }
        rule.onNodeWithText("99+").assertExists()
        rule.onNodeWithContentDescription("More than 99 unread messages").assertExists()
    }

    @Test
    fun unreadBadge_announcesCount() {
        rule.setContent { GimTheme { UnreadBadge(count = 7, muted = true) } }
        rule.onNodeWithText("7").assertExists()
        rule.onNodeWithContentDescription("7 unread messages, muted").assertExists()
    }

    @Test
    fun statusTicks_announceEachState() {
        val expected = mapOf(
            TickState.PENDING to "Sending",
            TickState.SENT to "Sent",
            TickState.DELIVERED to "Delivered",
            TickState.READ to "Read",
            TickState.FAILED to "Failed to send",
        )
        var state by mutableStateOf(TickState.PENDING)
        rule.setContent { GimTheme { StatusTicks(state = state) } }
        expected.forEach { (tick, label) ->
            state = tick
            rule.onNodeWithContentDescription(label).assertExists()
        }
    }

    @Test
    fun replyPreviewBar_closeInvokesCallback() {
        var closed = 0
        rule.setContent {
            GimTheme { ReplyPreviewBar(senderName = "Nikos", snippet = "See you in Corfu", onClose = { closed++ }) }
        }
        rule.onNodeWithContentDescription("Cancel reply").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun replyPreviewBar_withoutCallback_hasNoCloseButton() {
        rule.setContent {
            GimTheme { ReplyPreviewBar(senderName = "Nikos", snippet = "See you in Corfu", onClose = null) }
        }
        rule.onNodeWithContentDescription("Cancel reply").assertDoesNotExist()
    }

    @Test
    fun reactionChips_clickReportsReaction() {
        val thumbs = ReactionChipModel("👍", 3, mine = true)
        val anchor = ReactionChipModel("⚓", 1, mine = false)
        val clicked = mutableListOf<ReactionChipModel>()
        rule.setContent {
            GimTheme { ReactionChips(reactions = listOf(thumbs, anchor), onClick = { clicked += it }) }
        }
        rule.onNodeWithContentDescription("⚓, 1 reaction").assertIsNotSelected().performClick()
        rule.onNodeWithContentDescription("👍, 3 reactions, including yours")
            .assertIsSelected()
            .performClick()
        assertEquals(listOf(anchor, thumbs), clicked)
    }
}
