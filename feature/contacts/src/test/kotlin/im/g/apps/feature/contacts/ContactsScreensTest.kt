package im.g.apps.feature.contacts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun newChat_foundUser_showsUserAndStartChatTriggersCallback() {
        var started = 0
        rule.setContent {
            GimTheme {
                NewChatScreen(
                    state = NewChatUiState(query = "lukas", search = UserSearchState.Found(DemoData.lukas)),
                    onQueryChange = {},
                    onStartChat = { started++ },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Lukas Berger").assertExists()
        rule.onNodeWithText("@lukas").assertExists()
        rule.onNodeWithText("Start chat").assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun newChat_ownProfile_disablesStartChat() {
        rule.setContent {
            GimTheme {
                NewChatScreen(
                    state = NewChatUiState(
                        query = DemoData.me.handle,
                        search = UserSearchState.Found(DemoData.me),
                        selfUserId = DemoData.me.userId,
                    ),
                    onQueryChange = {},
                    onStartChat = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Start chat").assertIsNotEnabled()
    }

    @Test
    fun newGroup_nextAndCreateFollowValidation() {
        val vm = NewGroupViewModel(demoClient(scope))
        var openedChatId: String? = null
        rule.setContent {
            GimTheme { NewGroupRoute(vm, onOpenChat = { openedChatId = it }, onBack = {}) }
        }
        rule.onNodeWithText("Next").assertIsNotEnabled()

        rule.runOnIdle { vm.addMember(DemoData.ana) }
        rule.onNodeWithText("Ana Kovač").assertExists()
        rule.onNodeWithText("Next").assertIsEnabled()

        rule.onNodeWithContentDescription("Remove Ana Kovač").performClick()
        rule.onNodeWithText("Next").assertIsNotEnabled()

        rule.runOnIdle { vm.addMember(DemoData.ana) }
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Create group").assertIsNotEnabled()

        rule.onNode(hasSetTextAction()).performTextInput("Adriatic Run")
        rule.onNodeWithText("Create group").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertNotNull(openedChatId)
    }

    @Test
    fun groupInfo_removeMemberAsksForConfirmation() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        rule.onNodeWithText("Sofia Costa (You)").assertExists()
        rule.onNodeWithContentDescription("Remove Sofia Costa").assertDoesNotExist()

        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Remove Ana Kovač"))
        rule.onNodeWithContentDescription("Remove Ana Kovač").performClick()
        rule.onNodeWithText("Remove Ana Kovač from the group?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Remove Ana Kovač from the group?").assertDoesNotExist()
        rule.onNodeWithText("Ana Kovač").assertExists()

        rule.onNodeWithContentDescription("Remove Ana Kovač").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Ana Kovač").assertDoesNotExist()
        assertFalse(vm.state.value.members.any { it.user.userId == DemoData.ana.userId })
    }
}
