package im.g.apps.feature.chatlist

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.component.AvatarTestTags
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Muted and pinned marks, ticks, photos, the archived section and the long-press chat actions. */
@RunWith(AndroidJUnit4::class)
class ChatListMarksTest {
    @get:Rule
    val rule = createComposeRule()

    private val crew = ChatRowUi("chat-g", "Aegean Crew", null, PreviewSender.Member("Nikos"), "Table booked", ChatTimeLabel.Clock("09:58"), 0)
    private val lukas = ChatRowUi("chat-d", "Lukas Berger", null, PreviewSender.You, "See you then!", ChatTimeLabel.Weekday("Fri"), 0)

    private val actions = mutableListOf<Pair<String, ChatListAction>>()
    private val mutes = mutableListOf<Pair<String, MuteOption>>()
    private var openedArchived = 0
    private var back = 0
    private var errorsShown = 0

    private fun show(state: ChatListUiState, archived: Boolean = false) = rule.setContent {
        GimTheme {
            ChatListScreen(
                state = state,
                ownName = "Sofia Costa",
                onOpenChat = {},
                onNewChat = {},
                onNewGroup = {},
                onOpenSettings = {},
                archived = archived,
                onOpenArchived = { openedArchived++ },
                onBack = { back++ },
                onAction = { id, action -> actions += id to action },
                onMute = { id, option -> mutes += id to option },
                onErrorShown = { errorsShown++ },
            )
        }
    }

    private fun row(title: String) = rule.onNode(hasClickAction() and hasText(title))

    private fun description(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun muted_and_pinned_rows_show_their_marks() {
        show(ChatListUiState(rows = persistentListOf(crew.copy(muted = true, pinned = true), lukas)))

        rule.onNodeWithTag(ChatListTestTags.muted(crew.id), useUnmergedTree = true)
            .assert(description("Muted"))
        rule.onNodeWithTag(ChatListTestTags.pinned(crew.id), useUnmergedTree = true)
            .assert(description("Pinned"))
        rule.onNodeWithTag(ChatListTestTags.muted(lukas.id), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(ChatListTestTags.pinned(lukas.id), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun own_last_message_shows_its_tick_and_says_it() {
        show(ChatListUiState(rows = persistentListOf(crew, lukas.copy(tick = TickState.READ))))

        rule.onNodeWithTag(ChatListTestTags.tick(lukas.id), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ChatListTestTags.tick(crew.id), useUnmergedTree = true).assertDoesNotExist()
        row("Lukas Berger").assert(description("Lukas Berger, You: See you then!, Read, Fri"))
    }

    @Test
    fun failed_tick_is_said_too() {
        show(ChatListUiState(rows = persistentListOf(lukas.copy(tick = TickState.FAILED))))

        row("Lukas Berger").assert(description("Lukas Berger, You: See you then!, Not sent, Fri"))
    }

    @Test
    fun rows_with_a_photo_draw_it_and_others_show_initials() {
        show(ChatListUiState(rows = persistentListOf(crew.copy(avatarPath = "/cache/gim/avatars/crew"), lukas)))

        rule.onNode(
            hasTestTag(AvatarTestTags.PHOTO) and hasAnyAncestor(hasContentDescription("Aegean Crew", substring = true)),
            useUnmergedTree = true,
        ).assertExists()
        rule.onNode(
            hasTestTag(AvatarTestTags.PHOTO) and hasAnyAncestor(hasContentDescription("Lukas Berger", substring = true)),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        rule.onNodeWithText("LB", useUnmergedTree = true).assertExists()
    }

    @Test
    fun own_avatar_shows_the_own_photo() {
        show(ChatListUiState(rows = persistentListOf(crew), ownPhotoPath = "/cache/gim/avatars/u-sofia"))

        rule.onNode(
            hasTestTag(AvatarTestTags.PHOTO) and hasAnyAncestor(hasTestTag(ChatListTestTags.SETTINGS)),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun own_avatar_without_photo_shows_initials() {
        show(ChatListUiState(rows = persistentListOf(crew)))

        rule.onNode(
            hasTestTag(AvatarTestTags.PHOTO) and hasAnyAncestor(hasTestTag(ChatListTestTags.SETTINGS)),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun archived_entry_at_the_end_shows_the_count_and_opens_the_section() {
        show(ChatListUiState(rows = persistentListOf(crew), archivedRows = persistentListOf(lukas.copy(archived = true))))

        rule.onNodeWithText("Lukas Berger").assertDoesNotExist()
        rule.onNodeWithTag(ChatListTestTags.ARCHIVED).assert(hasText("Archived (1)")).performClick()

        assertEquals(1, openedArchived)
    }

    @Test
    fun no_archived_entry_without_archived_chats() {
        show(ChatListUiState(rows = persistentListOf(crew, lukas)))

        rule.onNodeWithTag(ChatListTestTags.ARCHIVED).assertDoesNotExist()
    }

    @Test
    fun only_archived_chats_still_show_the_entry_instead_of_the_empty_state() {
        show(ChatListUiState(archivedRows = persistentListOf(lukas.copy(archived = true))))

        rule.onNodeWithText("No chats yet").assertDoesNotExist()
        rule.onNodeWithText("Archived (1)").assertExists()
    }

    @Test
    fun archived_section_lists_archived_chats_with_back_and_unarchive() {
        val archived = lukas.copy(archived = true)
        show(ChatListUiState(rows = persistentListOf(crew), archivedRows = persistentListOf(archived)), archived = true)

        rule.onNodeWithText("Archived chats").assertExists()
        rule.onNodeWithText("Aegean Crew").assertDoesNotExist()
        rule.onNodeWithContentDescription("New message").assertDoesNotExist()
        rule.onNodeWithTag(ChatListTestTags.ARCHIVED).assertDoesNotExist()

        row("Lukas Berger").performTouchInput { longClick() }
        rule.onNodeWithText("Unarchive").performClick()
        assertEquals(listOf(archived.id to ChatListAction.UNARCHIVE), actions)

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun empty_archived_section_says_so() {
        show(ChatListUiState(rows = persistentListOf(crew)), archived = true)

        rule.onNodeWithText("No archived chats").assertExists()
    }

    @Test
    fun long_press_offers_mute_pin_and_archive() {
        show(ChatListUiState(rows = persistentListOf(crew, lukas)))

        row("Aegean Crew").performTouchInput { longClick() }
        rule.onNodeWithText("Unmute").assertDoesNotExist()
        rule.onNodeWithText("Archive").assertExists()
        rule.onNodeWithText("Pin").performClick()

        assertEquals(listOf(crew.id to ChatListAction.PIN), actions)
        rule.onNodeWithText("Archive").assertDoesNotExist()
    }

    @Test
    fun long_press_on_a_muted_pinned_chat_offers_the_undo() {
        show(ChatListUiState(rows = persistentListOf(crew.copy(muted = true, pinned = true))))

        row("Aegean Crew").performTouchInput { longClick() }
        rule.onNodeWithText("Unpin").assertExists()
        rule.onNodeWithText("Unmute").performClick()

        assertEquals(listOf(crew.id to ChatListAction.UNMUTE), actions)
    }

    @Test
    fun mute_asks_how_long() {
        show(ChatListUiState(rows = persistentListOf(crew)))

        row("Aegean Crew").performTouchInput { longClick() }
        rule.onNodeWithText("Mute").performClick()
        rule.onNodeWithText("For 1 hour").assertExists()
        rule.onNodeWithText("For 1 week").assertExists()
        rule.onNodeWithText("Always").assertExists()
        rule.onNodeWithText("For 8 hours").performClick()

        assertEquals(listOf(crew.id to MuteOption.EIGHT_HOURS), mutes)
        assertEquals(emptyList<Pair<String, ChatListAction>>(), actions)
        rule.onNodeWithText("For 1 hour").assertDoesNotExist()
    }

    @Test
    fun failed_action_shows_a_message() {
        show(ChatListUiState(rows = persistentListOf(crew), actionError = ChatListAction.PIN))

        rule.onNodeWithText("Couldn't pin this chat. Try again.").assertExists()
        rule.waitUntil { errorsShown == 1 }
    }
}
