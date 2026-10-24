package im.g.apps.feature.contacts

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.designsystem.R as DesignR
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
import org.robolectric.Shadows.shadowOf

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
    fun userPolicyErrors_showTheirMessages() {
        rule.setContent {
            GimTheme {
                Column {
                    Text(ContactsError.ContactNotAdmitted(listOf("Ana Kovač", "@nikos")).message())
                    Text(ContactsError.NotAdmittedByPeer.message())
                    Text(ContactsError.NotAdmitted(null).message())
                }
            }
        }
        rule.onNodeWithText("Couldn't add Ana Kovač and @nikos. Their server doesn't accept you. Try someone else.")
            .assertExists()
        rule.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(DesignR.string.gim_error_not_admitted_by_peer)).assertExists()
        rule.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(DesignR.string.gim_error_user_not_admitted)).assertExists()
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
    fun newChat_scanQrCode_showsScannerAndScannedIdOpensChat() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val friend = UserProfile("u" + "c".repeat(54) + "i", "cleo", "Cleo Marsh")
        val fake = demoClient(scope).apply { addDirectoryUser(friend) }
        val vm = NewChatViewModel(TestClient(fake))
        var opened: String? = null
        rule.setContent {
            GimTheme {
                NewChatRoute(
                    viewModel = vm,
                    onOpenChat = { opened = it },
                    onBack = {},
                    scanner = { onQrCode, _, modifier ->
                        Button(onClick = { onQrCode("hello ${friend.userId}") }, modifier = modifier) {
                            Text("Fake scanner")
                        }
                    },
                )
            }
        }
        rule.onNodeWithTag(NewChatTestTags.SCANNER).assertDoesNotExist()
        rule.onNodeWithTag(NewChatTestTags.SCAN_QR).assertExists().performClick()
        rule.onNodeWithText("Scan a QR code").assertExists()
        rule.onNodeWithTag(NewChatTestTags.SCANNER).performClick()
        rule.waitUntil(5_000) { opened != null }
        rule.onNodeWithTag(NewChatTestTags.SCANNER).assertDoesNotExist()
    }

    @Test
    fun newChat_scanNotice_isShownInTheScanner() {
        rule.setContent {
            GimTheme {
                NewChatScreen(
                    state = NewChatUiState(scannerOpen = true, scanNotice = ScanNotice.NOT_ACCOUNT_ID),
                    onQueryChange = {},
                    onStartChat = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText(
            "This QR code isn't a gim Account ID. Ask your friend to show the QR code from their Profile.",
        ).assertExists()
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

    @Test
    fun groupInfo_hidesTheChatActionsGroup() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        rule.onNodeWithText("Sofia Costa (You)").assertExists()
        rule.onNodeWithTag(ChatActionsTestTags.GROUP).assertDoesNotExist()
        rule.onNodeWithText("Chat actions").assertDoesNotExist()
        rule.onNodeWithText("Mute").assertDoesNotExist()
        rule.onNodeWithText("Report").assertDoesNotExist()
    }
}
