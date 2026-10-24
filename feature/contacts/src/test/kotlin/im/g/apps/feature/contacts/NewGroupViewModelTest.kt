package im.g.apps.feature.contacts

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.flow.chatListFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NewGroupViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val avatarDir by lazy { tmp.newFolder("avatars") }

    private fun TestScope.newViewModel(): Pair<NewGroupViewModel, TestClient> {
        val client = TestClient(demoClient(backgroundScope))
        return NewGroupViewModel(client, avatarDir, StandardTestDispatcher(testScheduler)) to client
    }

    @Test
    fun selectedMemberWithAPhoto_downloadsItOnce() = runTest {
        val fake = demoClient(backgroundScope).also { it.addDirectoryUser(DemoData.ana.copy(hasAvatar = true)) }
        val client = TestClient(fake)
        val vm = NewGroupViewModel(client, avatarDir, StandardTestDispatcher(testScheduler))
        vm.onQueryChange("ana")
        advanceUntilIdle()
        val ana = (vm.state.value.search as UserSearchState.Found).user
        vm.addMember(ana)
        vm.onQueryChange("ana")
        advanceUntilIdle()

        assertEquals(File(avatarDir, ana.userId).path, vm.state.value.photos[ana.userId])
        assertEquals(listOf(ana.userId), client.avatarFetches)
    }

    @Test
    fun addMember_fromSearch_selectsAndClearsQuery() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("ana")
        advanceUntilIdle()
        assertEquals(UserSearchState.Found(DemoData.ana), vm.state.value.search)

        vm.addMember(DemoData.ana)
        advanceUntilIdle()
        with(vm.state.value) {
            assertEquals(listOf(DemoData.ana), members)
            assertEquals("", query)
            assertEquals(UserSearchState.Idle, search)
            assertFalse(canAdd(DemoData.ana))
        }

        vm.addMember(DemoData.ana)
        vm.addMember(DemoData.me)
        assertEquals(listOf(DemoData.ana), vm.state.value.members)
    }

    @Test
    fun removeMember_dropsChip() = runTest {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.addMember(DemoData.ana)
        vm.addMember(DemoData.nikos)
        vm.removeMember(DemoData.ana.userId)
        assertEquals(listOf(DemoData.nikos), vm.state.value.members)
    }

    @Test
    fun next_requiresAtLeastOneMember() = runTest {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.canProceed)
        vm.next()
        assertEquals(NewGroupStep.MEMBERS, vm.state.value.step)

        vm.addMember(DemoData.ana)
        vm.next()
        assertEquals(NewGroupStep.NAME, vm.state.value.step)

        assertTrue(vm.back())
        assertEquals(NewGroupStep.MEMBERS, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun name_isRequiredAndCapped() = runTest {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.addMember(DemoData.ana)
        vm.next()
        assertFalse(vm.state.value.canCreate)
        vm.onNameChange("   ")
        assertFalse(vm.state.value.canCreate)
        vm.onNameChange("x".repeat(MAX_GROUP_NAME_LENGTH + 10))
        assertEquals(MAX_GROUP_NAME_LENGTH, vm.state.value.name.length)
        assertTrue(vm.state.value.canCreate)

        vm.onNameChange("")
        vm.create()
        advanceUntilIdle()
        assertFalse(vm.state.value.creating)
    }

    @Test
    fun create_createsGroupAndOpensIt() = runTest {
        val (vm, client) = newViewModel()
        advanceUntilIdle()
        vm.addMember(DemoData.ana)
        vm.addMember(DemoData.nikos)
        vm.next()
        vm.onNameChange("  Adriatic Run  ")
        vm.create()
        advanceUntilIdle()

        val event = vm.events.first() as NewGroupEvent.OpenChat
        val row = client.chatListFlow().first().single { it.chatId == event.chatId }
        assertEquals(ChatKind.GROUP, row.kind)
        assertEquals("Adriatic Run", row.name)
        assertEquals(listOf(DemoData.me, DemoData.ana, DemoData.nikos), client.chatMembers(event.chatId))
        assertFalse(vm.state.value.creating)
    }

    @Test
    fun create_partlyRefusedContacts_createsTheGroupWithTheAdmittedUsers() = runTest {
        val fake = demoClient(backgroundScope).also { it.refuseContacts(DemoData.nikos.userId) }
        val vm = NewGroupViewModel(fake, avatarDir)
        advanceUntilIdle()
        vm.addMember(DemoData.ana)
        vm.addMember(DemoData.nikos)
        vm.next()
        vm.onNameChange("Adriatic Run")
        vm.create()
        advanceUntilIdle()

        val event = vm.events.first() as NewGroupEvent.OpenChat
        assertEquals(listOf(DemoData.me, DemoData.ana), fake.chatMembers(event.chatId))
    }

    @Test
    fun create_allContactsRefused_namesThemAndCreatesNothing() = runTest {
        val fake = demoClient(backgroundScope).also { it.refuseContacts(DemoData.ana.userId, DemoData.nikos.userId) }
        val vm = NewGroupViewModel(fake, avatarDir)
        advanceUntilIdle()
        val before = fake.chatListFlow().first().size
        vm.addMember(DemoData.ana)
        vm.addMember(DemoData.nikos)
        vm.next()
        vm.onNameChange("Adriatic Run")
        vm.create()
        advanceUntilIdle()

        assertEquals(
            NewGroupEvent.ShowError(ContactsError.ContactNotAdmitted(listOf("Ana Kovač", "Nikos Papadakis"))),
            vm.events.first(),
        )
        assertEquals(before, fake.chatListFlow().first().size)
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun create_failure_emitsError() = runTest {
        val (vm, client) = newViewModel()
        client.createGroupChatError = GimException.InvalidInput("too many members")
        advanceUntilIdle()
        vm.addMember(DemoData.ana)
        vm.next()
        vm.onNameChange("Crew")
        vm.create()
        advanceUntilIdle()
        assertEquals(NewGroupEvent.ShowError(ContactsError.InvalidInput), vm.events.first())
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun create_withBlockedUser_saysTheyAreBlocked() = runTest {
        val (vm, client) = newViewModel()
        advanceUntilIdle()
        client.blockUser(DemoData.ana.userId)
        vm.addMember(DemoData.ana)
        vm.next()
        vm.onNameChange("Crew")
        vm.create()
        advanceUntilIdle()
        assertEquals(NewGroupEvent.ShowError(ContactsError.Blocked), vm.events.first())
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun create_withOnlyUnknownUsers_showsUserNotFoundAndCreatesNothing() = runTest {
        val (vm, client) = newViewModel()
        advanceUntilIdle()
        val before = client.chatListFlow().first().size
        vm.onQueryChange(UNKNOWN_USER_ID)
        advanceUntilIdle()
        val found = vm.state.value.search as UserSearchState.Found
        assertEquals(UNKNOWN_USER_ID, found.user.userId)
        assertTrue(client.lookups.isEmpty())
        vm.addMember(found.user)
        vm.next()
        vm.onNameChange("Ghosts")
        vm.create()
        advanceUntilIdle()
        assertEquals(NewGroupEvent.ShowError(ContactsError.UserNotFound), vm.events.first())
        assertEquals(before, client.chatListFlow().first().size)
    }

    private companion object {
        /** Well-formed, but no such user on the hub. */
        const val UNKNOWN_USER_ID = "u" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" + "a"
    }
}
