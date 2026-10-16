package im.g.apps.feature.hubs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val home = HubInfo(HUB_A_ID, HUB_A, HubState.VERIFIED, 0, isPrimary = true, isHome = true, signedIn = true)
    private val second = HubInfo(HUB_B_ID, HUB_B, HubState.VERIFIED, 1, isPrimary = false, isHome = false, signedIn = true)
    private val shared = HubInfo("hub-shared", null, HubState.UNVERIFIED, 2, isPrimary = false, isHome = false, signedIn = false)
    private val gone = HubInfo("hub-gone", "https://gone.example.eu", HubState.REMOVED, 3, false, false, false)
    private val listed = HubsUiState(
        hubs = persistentListOf(home, second, shared),
        removed = persistentListOf(gone),
        loading = false,
    )
    private val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = fakeScope.cancel()

    private fun show(state: HubsUiState, actions: HubsActions = HubsActions()) =
        rule.setContent { GimTheme { HubsScreen(state, actions) } }

    private fun row(hubId: String) = hasAnyAncestor(hasTestTag(HubsTestTags.ROW_PREFIX + hubId))

    private fun button(text: String, hubId: String) = rule.onNode(hasText(text) and hasClickAction() and row(hubId))

    @Test
    fun lists_hosts_states_and_badges_with_removed_hubs_last() {
        show(listed)

        rule.onNode(hasText("10.0.2.2:8080") and row(HUB_A_ID)).assertExists()
        rule.onNode(hasText("Home") and row(HUB_A_ID)).assertExists()
        rule.onNode(hasText("Primary") and row(HUB_A_ID)).assertExists()
        rule.onNode(hasText("Verified · Signed in") and row(HUB_A_ID)).assertExists()
        rule.onNode(hasText("10.0.2.2:8081") and row(HUB_B_ID)).assertExists()
        rule.onNode(hasText("Home") and row(HUB_B_ID)).assertDoesNotExist()
        rule.onNode(hasText("Unknown address") and row("hub-shared")).assertExists()
        rule.onNode(hasText("Unverified · Not signed in") and row("hub-shared")).assertExists()

        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HubsTestTags.ROW_PREFIX + "hub-gone"))
        rule.onNodeWithText("Removed").assertExists()
        rule.onNode(hasText("gone.example.eu") and row("hub-gone")).assertExists()
        rule.onNode(hasText("Removed · Not signed in") and row("hub-gone")).assertExists()
    }

    @Test
    fun row_actions_are_enabled_only_where_allowed() {
        val primaries = mutableListOf<HubInfo>()
        val removals = mutableListOf<HubInfo>()
        show(listed, HubsActions(onMakePrimary = { primaries += it }, onRemove = { removals += it }))

        button("Make primary", HUB_A_ID).assertIsNotEnabled()
        button("Make primary", "hub-shared").assertIsNotEnabled()
        button("Remove", HUB_A_ID).assertIsEnabled()
        button("Make primary", HUB_B_ID).performClick()
        button("Remove", HUB_B_ID).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(HubsTestTags.ROW_PREFIX + "hub-gone"))
        button("Remove", "hub-gone").assertIsNotEnabled()
        button("Make primary", "hub-gone").assertIsNotEnabled()

        assertEquals(listOf(second), primaries)
        assertEquals(listOf(second), removals)
    }

    @Test
    fun add_dialog_shows_errors_inline() {
        var submitted = 0
        show(
            listed.copy(addHub = AddHubState(url = "http://relay.example.eu", error = HubsError.NotVerified)),
            HubsActions(onSubmitAddHub = { submitted++ }),
        )

        rule.onNodeWithText("Add a hub").assertExists()
        rule.onNodeWithTag(HubsTestTags.ADD_ERROR, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("The hub's address does not match its public address", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(HubsTestTags.ADD_CONFIRM).performClick()
        assertEquals(1, submitted)
    }

    @Test
    fun add_error_texts() {
        var state by mutableStateOf(listed)
        rule.setContent { GimTheme { HubsScreen(state, HubsActions()) } }
        mapOf(
            HubsError.InvalidUrl to "Not a gim hub URL",
            HubsError.Unreachable to "Cannot reach the hub",
            HubsError.RetryLater(30) to "Try again in 30 s",
        ).forEach { (error, text) ->
            state = listed.copy(addHub = AddHubState("x", error = error))
            rule.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun owned_groups_warning_confirms_or_cancels() {
        var confirmed = 0
        var dismissed = 0
        show(
            listed.copy(error = HubsError.OwnsGroups(HUB_B_ID, 2)),
            HubsActions(onConfirmRemoveOwnedGroups = { confirmed++ }, onErrorShown = { dismissed++ }),
        )

        rule.onNodeWithText(
            "This hub runs 2 of your group chats. They keep running there; you reach them through your other hubs.",
        ).assertExists()
        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun remove_explanations_are_shown_until_acknowledged() {
        var shown = 0
        show(listed.copy(error = HubsError.HomeHub), HubsActions(onErrorShown = { shown++ }))

        rule.onNodeWithText("This is your home hub; make another hub primary and keep this one as secondary").assertExists()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, shown)
    }

    @Test
    fun owned_groups_flow_with_the_fake_removes_the_hub() {
        val fake = runBlocking {
            signedInClient(fakeScope).apply {
                addHub(HUB_B)
                simulateChatOwnedBy(createGroupChat("Sailing club", emptyList()), HUB_B_ID)
            }
        }
        val vm = HubsViewModel(fake)
        rule.setContent { GimTheme { HubsRoute(vm, onBack = {}) } }

        rule.waitUntil { rule.onAllNodes(row(HUB_B_ID)).fetchSemanticsNodes().isNotEmpty() }
        button("Remove", HUB_B_ID).performClick()
        rule.waitUntil { rule.onAllNodes(hasText("This hub runs 1 of", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Confirm").performClick()
        rule.waitUntil { rule.onAllNodes(hasText("Removed · Not signed in")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithContentDescription("Add hub").assertIsEnabled()
        assertEquals(HubState.REMOVED, runBlocking { fake.hubs() }.last().state)
    }
}
