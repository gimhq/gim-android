package im.g.apps.feature.contacts

import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.flow.chatListFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val NOW = 1_760_000_000_000L

@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val marta = UserProfile("u-marta", "marta", "Marta Rossi")

    private lateinit var fake: FakeGimClient
    private lateinit var client: TestClient

    private fun TestScope.newViewModel(
        chatId: String = DemoData.GROUP_CHAT_ID,
        reportAvailable: Boolean = false,
    ): GroupInfoViewModel {
        fake = demoClient(backgroundScope).also { it.addDirectoryUser(marta) }
        client = TestClient(fake)
        return GroupInfoViewModel(client, chatId, reportAvailable, clock = { NOW })
    }

    private fun GroupInfoViewModel.memberIds() = state.value.members.map { it.user.userId }

    @Test
    fun group_loadsDetailsAndMarksSelf() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Aegean Crew", state.chat?.name)
        assertTrue(state.isGroup)
        assertEquals(
            listOf(DemoData.me, DemoData.ana, DemoData.lukas, DemoData.nikos).map { it.userId },
            vm.memberIds(),
        )
        assertEquals(listOf(true, false, false, false), state.members.map { it.isSelf })
        assertFalse(state.membersUnavailable)
    }

    @Test
    fun direct_showsPeerWithoutEditing() = runTest {
        val vm = newViewModel(DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        assertFalse(vm.state.value.isGroup)
        assertEquals(DemoData.lukas, vm.state.value.peer)

        vm.requestRemove(DemoData.lukas)
        vm.requestLeave()
        vm.openAddMember()
        vm.startRename()
        with(vm.state.value) {
            assertNull(pendingRemoval)
            assertFalse(confirmingLeave)
            assertNull(addMemberDialog)
            assertNull(renameDraft)
        }
    }

    @Test
    fun addMember_viaSearchDialog_updatesMembers() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.openAddMember()
        vm.onAddMemberQueryChange("@Marta")
        advanceUntilIdle()
        assertEquals(UserSearchState.Found(marta), vm.state.value.addMemberDialog?.search)

        vm.addMember(marta)
        advanceUntilIdle()
        assertNull(vm.state.value.addMemberDialog)
        assertTrue(marta.userId in vm.memberIds())
        assertEquals(5, vm.state.value.chat?.memberCount)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun addMember_existingMember_isIgnored() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.openAddMember()
        vm.addMember(DemoData.ana)
        assertFalse(vm.state.value.canAdd(DemoData.ana))
        assertTrue(vm.state.value.addMemberDialog != null)
    }

    @Test
    fun removeMember_requiresConfirmation() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.requestRemove(DemoData.me)
        assertNull(vm.state.value.pendingRemoval)

        vm.requestRemove(DemoData.ana)
        assertEquals(DemoData.ana, vm.state.value.pendingRemoval)
        vm.dismissDialog()
        advanceUntilIdle()
        assertTrue(DemoData.ana.userId in vm.memberIds())

        vm.requestRemove(DemoData.ana)
        vm.confirmRemove()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingRemoval)
        assertFalse(DemoData.ana.userId in vm.memberIds())
    }

    @Test
    fun removeMember_suspended_showsSuspendedAndKeepsTheGroup() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val hub = fake.addHub(HUB_B)
        fake.simulateSuspension(hub.hubId)
        vm.requestRemove(DemoData.ana)
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.Suspended(HUB_B)), vm.events.first())
        assertTrue(DemoData.ana.userId in vm.memberIds())
        assertEquals("Aegean Crew", vm.state.value.chat?.name)
        assertFalse(vm.state.value.busy)
        assertNotNull(fake.session())
    }

    @Test
    fun memberChangeAndLeave_notAdmittedByPeer_showChatHubRefusal() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.simulateNotAdmittedByPeer(DemoData.GROUP_CHAT_ID)

        vm.requestRemove(DemoData.ana)
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotAdmittedByPeer), vm.events.first())
        assertTrue(DemoData.ana.userId in vm.memberIds())

        vm.addMember(marta)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotAdmittedByPeer), vm.events.first())
        assertFalse(marta.userId in vm.memberIds())

        vm.requestLeave()
        vm.confirmLeave()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotAdmittedByPeer), vm.events.first())
        assertFalse(vm.state.value.busy)
        assertNotNull(fake.session())
    }

    @Test
    fun addMember_contactNotAdmitted_namesTheUser() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.refuseContacts(marta.userId)

        vm.addMember(marta)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.ContactNotAdmitted(listOf("Marta Rossi"))), vm.events.first())
        assertFalse(marta.userId in vm.memberIds())
    }

    @Test
    fun removeMember_failure_emitsError() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        client.removeMemberError = GimException.NotFound("member")
        vm.requestRemove(DemoData.ana)
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotFound), vm.events.first())
        assertTrue(DemoData.ana.userId in vm.memberIds())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun externalMembershipChange_refreshesMembers() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.removeMember(DemoData.GROUP_CHAT_ID, DemoData.nikos.userId)
        advanceUntilIdle()
        assertFalse(DemoData.nikos.userId in vm.memberIds())
    }

    @Test
    fun membersNotImplemented_fallsBackToMemberCount() = runTest {
        val vm = newViewModel()
        client.chatMembersError = GimException.NotImplemented()
        advanceUntilIdle()
        with(vm.state.value) {
            assertTrue(membersUnavailable)
            assertTrue(members.isEmpty())
            assertEquals(4, chat?.memberCount)
        }
    }

    @Test
    fun membersFailure_emitsError() = runTest {
        val vm = newViewModel()
        client.chatMembersError = GimException.Storage("disk full")
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.Unknown), vm.events.first())
    }

    @Test
    fun rename_updatesName() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.startRename()
        assertEquals("Aegean Crew", vm.state.value.renameDraft)
        vm.onRenameChange("  ")
        assertFalse(vm.state.value.canRename)
        vm.onRenameChange(" Cyclades Crew ")
        vm.confirmRename()
        advanceUntilIdle()
        assertNull(vm.state.value.renameDraft)
        assertEquals("Cyclades Crew", vm.state.value.chat?.name)
    }

    @Test
    fun rename_invalidInput_showsErrorAndKeepsOldName() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        client.setGroupInfoError = GimException.InvalidInput("name too long")
        vm.startRename()
        vm.onRenameChange("Cyclades Crew")
        vm.confirmRename()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.InvalidInput), vm.events.first())
        assertEquals("Aegean Crew", vm.state.value.chat?.name)
        assertNull(vm.state.value.renameDraft)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun rename_notImplementedByOlderCore_showsGenericNotSupported() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        client.setGroupInfoError = GimException.NotImplemented()
        vm.startRename()
        vm.onRenameChange("Cyclades Crew")
        vm.confirmRename()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotSupported), vm.events.first())
        assertEquals("Aegean Crew", vm.state.value.chat?.name)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun leave_requiresConfirmation_emitsLeftChat_andFollowsTheChatState() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.confirmLeave()
        advanceUntilIdle()
        assertFalse(vm.state.value.leaving)

        vm.requestLeave()
        assertTrue(vm.state.value.confirmingLeave)
        vm.confirmLeave()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.LeftChat, vm.events.first())
        assertTrue(vm.state.value.leaving)
        // Another member has not committed the leave yet: the chat stays.
        assertTrue(DemoData.GROUP_CHAT_ID in fake.chatIds())

        vm.requestLeave()
        assertFalse(vm.state.value.confirmingLeave)
        fake.commitLeave(DemoData.GROUP_CHAT_ID)
        advanceUntilIdle()
        assertFalse(vm.state.value.leaving)
        assertTrue(vm.state.value.left)
        vm.requestLeave()
        assertFalse(vm.state.value.confirmingLeave)
    }

    @Test
    fun chatNotActive_showsTheReadOnlyError() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        client.removeMemberError = GimException.ChatNotActive(DemoData.GROUP_CHAT_ID, ChatState.LEFT)
        vm.requestRemove(DemoData.lukas)
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.ChatNotActive), vm.events.first())
    }

    @Test
    fun addMember_unknownUserId_showsUserNotFound() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val ghost = "u" + "z".repeat(54) + "a"
        vm.openAddMember()
        vm.onAddMemberQueryChange(ghost)
        advanceUntilIdle()
        val found = (vm.state.value.addMemberDialog?.search as UserSearchState.Found).user
        vm.addMember(found)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.UserNotFound), vm.events.first())
        assertFalse(ghost in vm.memberIds())
    }

    @Test
    fun chatActions_group_offersMutePinArchiveOnly() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(listOf(ChatAction.MUTE, ChatAction.PIN, ChatAction.ARCHIVE), vm.state.value.chatActions)
    }

    @Test
    fun chatActions_direct_addsBlock_andReportOnlyWhenAvailable() = runTest {
        val vm = newViewModel(DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        assertEquals(
            listOf(ChatAction.MUTE, ChatAction.PIN, ChatAction.ARCHIVE, ChatAction.BLOCK),
            vm.state.value.chatActions,
        )

        val withReport = newViewModel(DemoData.DIRECT_CHAT_ID, reportAvailable = true)
        advanceUntilIdle()
        assertEquals(ChatAction.REPORT, withReport.state.value.chatActions.last())
    }

    @Test
    fun mute_optionsSetTheEndTime_andUnmuteClearsIt() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val expected = mapOf(
            MuteOption.ONE_HOUR to NOW + 60 * 60_000L,
            MuteOption.EIGHT_HOURS to NOW + 8 * 60 * 60_000L,
            MuteOption.ONE_WEEK to NOW + 7 * 24 * 60 * 60_000L,
            MuteOption.ALWAYS to fake.mutedForever(),
        )
        for ((option, until) in expected) {
            if (ChatAction.UNMUTE in vm.state.value.chatActions) {
                vm.onChatAction(ChatAction.UNMUTE)
                advanceUntilIdle()
            }
            vm.onChatAction(ChatAction.MUTE)
            assertTrue(vm.state.value.choosingMute)
            vm.mute(option)
            advanceUntilIdle()
            assertFalse(vm.state.value.choosingMute)
            assertEquals(until, vm.state.value.chat?.mutedUntilMs)
            assertEquals(ChatAction.UNMUTE, vm.state.value.chatActions.first())
            assertEquals(option == MuteOption.ALWAYS, vm.state.value.mutedForever)
        }

        vm.onChatAction(ChatAction.UNMUTE)
        advanceUntilIdle()
        assertEquals(0L, vm.state.value.chat?.mutedUntilMs)
        assertEquals(ChatAction.MUTE, vm.state.value.chatActions.first())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun mute_withoutTheDialog_doesNothing() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.mute(MuteOption.ONE_HOUR)
        advanceUntilIdle()
        assertEquals(0L, vm.state.value.chat?.mutedUntilMs)
        vm.onChatAction(ChatAction.MUTE)
        vm.dismissDialog()
        assertFalse(vm.state.value.choosingMute)
    }

    @Test
    fun pinAndArchive_toggle() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onChatAction(ChatAction.PIN)
        advanceUntilIdle()
        vm.onChatAction(ChatAction.ARCHIVE)
        advanceUntilIdle()
        assertTrue(vm.state.value.chat!!.pinned)
        assertTrue(vm.state.value.chat!!.archived)
        assertEquals(listOf(ChatAction.MUTE, ChatAction.UNPIN, ChatAction.UNARCHIVE), vm.state.value.chatActions)

        vm.onChatAction(ChatAction.UNPIN)
        advanceUntilIdle()
        vm.onChatAction(ChatAction.UNARCHIVE)
        advanceUntilIdle()
        assertFalse(vm.state.value.chat!!.pinned)
        assertFalse(vm.state.value.chat!!.archived)
    }

    @Test
    fun actionNotOffered_isIgnored() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onChatAction(ChatAction.UNPIN)
        vm.onChatAction(ChatAction.BLOCK)
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmingBlock)
        assertFalse(vm.state.value.chat!!.pinned)
    }

    @Test
    fun block_asksFirst_thenBlocksThePeer_andUnblockUndoesIt() = runTest {
        val vm = newViewModel(DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        vm.onChatAction(ChatAction.BLOCK)
        assertTrue(vm.state.value.confirmingBlock)
        vm.dismissDialog()
        assertFalse(vm.state.value.confirmingBlock)
        vm.confirmBlock()
        advanceUntilIdle()
        assertFalse(vm.state.value.chat!!.blocked)

        vm.onChatAction(ChatAction.BLOCK)
        vm.confirmBlock()
        advanceUntilIdle()
        assertTrue(vm.state.value.chat!!.blocked)
        assertTrue(DemoData.lukas.userId in fake.privacy().blockedUserIds)
        assertTrue(ChatAction.UNBLOCK in vm.state.value.chatActions)

        vm.onChatAction(ChatAction.UNBLOCK)
        assertTrue(vm.state.value.confirmingBlock)
        vm.confirmBlock()
        advanceUntilIdle()
        assertFalse(vm.state.value.chat!!.blocked)
        assertTrue(fake.privacy().blockedUserIds.isEmpty())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun chatActionFailure_saysWhichActionFailed_andKeepsTheState() = runTest {
        val vm = newViewModel(DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        fake.failNext("setChatMuted", GimException.Storage("disk full"))
        vm.onChatAction(ChatAction.MUTE)
        vm.mute(MuteOption.ONE_HOUR)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.ChatActionFailed(ChatAction.MUTE)), vm.events.first())
        assertEquals(0L, vm.state.value.chat?.mutedUntilMs)
        assertFalse(vm.state.value.busy)

        fake.failNext("blockUser", GimException.Internal("boom"))
        vm.onChatAction(ChatAction.BLOCK)
        vm.confirmBlock()
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.ChatActionFailed(ChatAction.BLOCK)), vm.events.first())
        assertFalse(vm.state.value.chat!!.blocked)

        fake.failNext("setChatPinned", GimException.NotFound("chat"))
        vm.onChatAction(ChatAction.PIN)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.NotFound), vm.events.first())
    }

    @Test
    fun report_opensTheReportForThePeer() = runTest {
        val vm = newViewModel(DemoData.DIRECT_CHAT_ID, reportAvailable = true)
        advanceUntilIdle()
        vm.onChatAction(ChatAction.REPORT)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.OpenReport(DemoData.lukas.userId, DemoData.DIRECT_CHAT_ID), vm.events.first())
    }

    @Test
    fun addMember_blockedUser_saysTheyAreBlocked() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.blockUser(marta.userId)
        vm.addMember(marta)
        advanceUntilIdle()
        assertEquals(GroupInfoEvent.ShowError(ContactsError.Blocked), vm.events.first())
        assertFalse(marta.userId in vm.memberIds())
    }

    private suspend fun FakeGimClient.chatIds(): List<String> = chatListFlow().first().map { it.chatId }
}
