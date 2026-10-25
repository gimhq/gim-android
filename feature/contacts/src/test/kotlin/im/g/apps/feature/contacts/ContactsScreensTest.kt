package im.g.apps.feature.contacts

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.AvatarTestTags
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ContactsScreensTest {

    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val avatarDir by lazy { tmp.newFolder("avatars") }

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    /** Scrolls the screen's lazy list until a node matching [matcher] is composed and visible. */
    private fun scrollTo(matcher: SemanticsMatcher) = rule.onNode(hasScrollAction()).performScrollToNode(matcher)

    private fun scrollToTag(tag: String) = scrollTo(hasTestTag(tag))

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
    fun userRow_readsTheNameOnce_withoutTheInitials() {
        rule.setContent { GimTheme { UserRow(user = DemoData.ana) } }
        rule.onAllNodesWithText("AK").assertCountEquals(0)
        rule.onAllNodes(hasText("Ana Kovač", substring = true) or hasContentDescription("Ana Kovač", substring = true))
            .assertCountEquals(1)
    }

    @Test
    fun userRow_withAPhoto_showsIt() {
        rule.setContent { GimTheme { UserRow(user = DemoData.ana, photoPath = "/cache/gim/avatars/u-ana") } }
        rule.onNodeWithTag(AvatarTestTags.PHOTO, useUnmergedTree = true).assertExists()
    }

    @Test
    fun userRow_withoutAPhoto_showsTheInitials() {
        rule.setContent { GimTheme { UserRow(user = DemoData.ana) } }
        rule.onNodeWithTag(AvatarTestTags.PHOTO, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("AK", useUnmergedTree = true).assertExists()
    }

    @Test
    fun directChatInfo_headerShowsThePeerPhoto() {
        val fake = demoClient(scope).apply {
            addDirectoryUser(DemoData.lukas.copy(hasAvatar = true))
            // The chat list row picks up the new profile with the next change of the chat.
            simulateIncoming(DemoData.DIRECT_CHAT_ID, "New photo", from = DemoData.lukas.userId)
        }
        val vm = GroupInfoViewModel(fake, DemoData.DIRECT_CHAT_ID, avatarDir)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        rule.waitUntil(5_000) { vm.state.value.headerPhotoPath != null }
        rule.onNodeWithTag(AvatarTestTags.PHOTO, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("LB").assertCountEquals(0)
    }

    @Test
    fun groupInfo_avatarsAreDecorative() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID, avatarDir)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        rule.onNodeWithText("Sofia Costa (You)").assertExists()
        // Header, own row and a member row.
        listOf("AC", "SC", "AK").forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
        rule.onAllNodesWithText("Aegean Crew").assertCountEquals(1)
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
        val vm = NewChatViewModel(TestClient(fake), avatarDir)
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
        val vm = NewGroupViewModel(demoClient(scope), avatarDir)
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
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID, avatarDir)
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
    fun groupInfo_showsTheChatActionsOfAGroup_withoutBlockOrReport() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID, avatarDir, reportAvailable = true)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        rule.onNodeWithText("Sofia Costa (You)").assertExists()
        scrollToTag(ChatActionsTestTags.action(ChatAction.ARCHIVE))
        rule.onNodeWithTag(ChatActionsTestTags.GROUP).assertExists()
        rule.onNodeWithText("Chat actions").assertExists()
        rule.onNodeWithText("Mute").assertExists()
        rule.onNodeWithText("Pin").assertExists()
        rule.onNodeWithText("Archive").assertExists()
        rule.onNodeWithText("Block").assertDoesNotExist()
        rule.onNodeWithText("Report").assertDoesNotExist()
    }

    @Test
    fun directChatInfo_hidesReportWithoutAKeepServer() {
        val fake = demoClient(scope)
        val vm = GroupInfoViewModel(fake, DemoData.DIRECT_CHAT_ID, avatarDir, reportAvailable = false)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        scrollToTag(ChatActionsTestTags.action(ChatAction.BLOCK))
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.BLOCK)).assertExists()
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.REPORT)).assertDoesNotExist()
        rule.onNodeWithText("Report").assertDoesNotExist()
    }

    @Test
    fun directChatInfo_showsReportWithAKeepServer_andOpensItForThePeer() {
        val fake = FakeGimClient.withDemoData(scope, outboxDelays = null, keepServerUrl = "https://keep.example.eu")
        val vm = GroupInfoViewModel(fake, DemoData.DIRECT_CHAT_ID, avatarDir, reportAvailable = true)
        var reported: Pair<String, String>? = null
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}, onReport = { u, c -> reported = u to c }) } }

        scrollToTag(ChatActionsTestTags.action(ChatAction.REPORT))
        rule.onNodeWithText("Report").assertExists()
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.REPORT)).performClick()
        rule.waitUntil(5_000) { reported != null }
        assertEquals(DemoData.lukas.userId to DemoData.DIRECT_CHAT_ID, reported)
    }

    @Test
    fun chatInfo_muteForAnHour_showsUnmuteAndUntilWhen() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.DIRECT_CHAT_ID, avatarDir)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        scrollToTag(ChatActionsTestTags.action(ChatAction.MUTE))
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.MUTE)).performClick()
        rule.onNodeWithText("Mute this chat").assertExists()
        rule.onNodeWithText("Always").assertExists()
        rule.onNodeWithTag(ChatActionsTestTags.muteOption(MuteOption.ONE_HOUR)).performClick()
        rule.onNodeWithText("Mute this chat").assertDoesNotExist()
        rule.waitUntil(5_000) { vm.state.value.chatActions.firstOrNull() == ChatAction.UNMUTE }
        rule.onNodeWithText("Unmute").assertExists()
        rule.onNode(hasText("Muted until", substring = true)).assertExists()

        rule.onNodeWithText("Unmute").performClick()
        rule.waitUntil(5_000) { vm.state.value.chatActions.firstOrNull() == ChatAction.MUTE }
        rule.onNode(hasText("Muted until", substring = true)).assertDoesNotExist()
    }

    @Test
    fun chatInfo_muteAlways_saysUntilYouUnmute() {
        val vm = GroupInfoViewModel(demoClient(scope), DemoData.GROUP_CHAT_ID, avatarDir)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        scrollToTag(ChatActionsTestTags.action(ChatAction.MUTE))
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.MUTE)).performClick()
        rule.onNodeWithTag(ChatActionsTestTags.muteOption(MuteOption.ALWAYS)).performClick()
        rule.waitUntil(5_000) { vm.state.value.mutedForever }
        rule.onNodeWithText("Muted until you unmute it").assertExists()
    }

    @Test
    fun chatInfo_blockAsksWithAnExplanation_thenShowsUnblock() {
        val fake = demoClient(scope)
        val vm = GroupInfoViewModel(fake, DemoData.DIRECT_CHAT_ID, avatarDir)
        rule.setContent { GimTheme { GroupInfoRoute(vm, onBack = {}) } }

        scrollToTag(ChatActionsTestTags.action(ChatAction.BLOCK))
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.BLOCK)).performClick()
        rule.onNodeWithText("Block Lukas Berger?").assertExists()
        rule.onNode(hasText("They can't send you messages.", substring = true)).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Block Lukas Berger?").assertDoesNotExist()

        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.BLOCK)).performClick()
        rule.onNode(hasText("Block") and hasClickAction() and hasAnyAncestor(hasTestTag(ChatActionsTestTags.BLOCK_CONFIRM)))
            .performClick()
        rule.waitUntil(5_000) { vm.state.value.chat?.blocked == true }
        scrollToTag(ChatActionsTestTags.action(ChatAction.UNBLOCK))
        rule.onNodeWithTag(ChatActionsTestTags.action(ChatAction.UNBLOCK)).performClick()
        rule.onNodeWithText("Unblock Lukas Berger?").assertExists()
    }

    @Test
    fun blockedError_saysHowToUnblock() {
        rule.setContent { GimTheme { Text(ContactsError.Blocked.message()) } }
        rule.onNodeWithText("You blocked this person. Unblock them in Settings > Privacy to start a chat.").assertExists()
    }

    @Test
    fun report_formShowsTheWarning_andSendIsEnabledOnceAReasonIsPicked() {
        val fake = FakeGimClient.withDemoData(scope, outboxDelays = null, keepServerUrl = "https://keep.example.eu")
        val vm = ReportViewModel(fake, DemoData.lukas.userId, DemoData.DIRECT_CHAT_ID)
        rule.setContent { GimTheme { ReportRoute(vm, onBack = {}) } }

        rule.onNodeWithText("Report Lukas Berger").assertExists()
        rule.waitUntil(5_000) { vm.state.value.messages != null }
        scrollToTag(ReportTestTags.SEND)
        rule.onNodeWithTag(ReportTestTags.SEND).assertIsNotEnabled()
        scrollToTag(ReportTestTags.WARNING)
        rule.onNodeWithText(
            "The messages you pick are sent to the moderators without encryption. The moderators can read them.",
        ).assertExists()

        scrollToTag(ReportTestTags.reason(ReportReason.SPAM))
        rule.onNodeWithTag(ReportTestTags.reason(ReportReason.SPAM)).performClick()
        scrollToTag(ReportTestTags.NOTE)
        rule.onNodeWithText("0/1000").assertExists()
        rule.onNodeWithTag(ReportTestTags.NOTE).performTextInput("Ads")
        rule.onNodeWithText("3/1000").assertExists()
        val first = vm.state.value.messages!!.first().messageId
        scrollToTag(ReportTestTags.message(first))
        rule.onNodeWithTag(ReportTestTags.message(first)).performClick()
        rule.onNodeWithText("1/50 picked").assertExists()
        scrollToTag(ReportTestTags.ALSO_BLOCK)
        rule.onNodeWithTag(ReportTestTags.ALSO_BLOCK).performClick()
        scrollToTag(ReportTestTags.SEND)
        rule.onNodeWithTag(ReportTestTags.SEND).assertIsEnabled().performClick()

        rule.waitUntil(5_000) { vm.state.value.sent }
        rule.onNodeWithTag(ReportTestTags.SENT).assertExists()
        rule.onNodeWithText("Thanks. Your report was sent.").assertExists()
        rule.onNodeWithText("You also blocked them. They can't send you messages.").assertExists()
        assertEquals(listOf(first), fake.reports.single().messageIds)
    }

    @Test
    fun report_noMessages_saysThereIsNothingToAdd() {
        rule.setContent {
            GimTheme { ReportScreen(state = ReportUiState(name = "Nikos", messages = persistentListOf()), actions = ReportActions()) }
        }
        rule.onNodeWithText("Report Nikos").assertExists()
        scrollTo(hasText("There are no messages from them to add."))
        rule.onNodeWithText("There are no messages from them to add.").assertExists()
    }

    @Test
    fun report_sentWithBlockFailure_saysHowToBlock() {
        var done = 0
        rule.setContent {
            GimTheme {
                ReportScreen(
                    state = ReportUiState(sent = true, blockFailed = true),
                    actions = ReportActions(onDone = { done++ }),
                )
            }
        }
        rule.onNodeWithText("Couldn't block them. You can block them in chat info.").assertExists()
        rule.onNodeWithTag(ReportTestTags.DONE).performClick()
        assertEquals(1, done)
    }
}
