package im.g.apps.feature.contacts

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var fake: FakeGimClient

    private fun TestScope.newViewModel(
        chatId: String = DemoData.DIRECT_CHAT_ID,
        userId: String = DemoData.lukas.userId,
        messageId: String? = null,
        extraMessages: Int = 0,
    ): ReportViewModel {
        fake = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null, pageSize = 200, keepServerUrl = KEEP)
        repeat(extraMessages) { fake.simulateIncoming(chatId, "Spam $it", from = userId) }
        return ReportViewModel(fake, userId, chatId, messageId)
    }

    @Test
    fun loadsTheirMessagesWithText_newestFirst_andTheirName() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val messages = vm.state.value.messages!!
        assertEquals("Lukas Berger", vm.state.value.name)
        assertEquals("Let's do a call on Thursday to finalise.", messages.first().text)
        assertTrue(messages.any { it.text == "The boat at Porto Antico" })
        assertFalse(messages.any { it.text == "Absolutely. Genoa to Split, right?" })
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun preselectedMessage_isPicked() = runTest {
        val vm = newViewModel(extraMessages = 1)
        advanceUntilIdle()
        val id = vm.state.value.messages!!.first().messageId
        val preselected = newViewModel(messageId = id, extraMessages = 1)
        advanceUntilIdle()
        assertEquals(setOf(id), preselected.state.value.selected)
    }

    @Test
    fun send_requiresAReason() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
        vm.send()
        advanceUntilIdle()
        assertTrue(fake.reports.isEmpty())

        vm.onReasonChange(ReportReason.SPAM)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun picking_stopsAtFiftyMessages_andUnpickingFreesASlot() = runTest {
        val vm = newViewModel(extraMessages = 60)
        advanceUntilIdle()
        val ids = vm.state.value.messages!!.map { it.messageId }
        ids.take(55).forEach(vm::toggleMessage)
        assertEquals(MAX_REPORT_MESSAGES, vm.state.value.selected.size)
        assertFalse(ids[50] in vm.state.value.selected)
        assertFalse(vm.state.value.canPick(ids[50]))

        vm.toggleMessage(ids[0])
        assertTrue(vm.state.value.canPick(ids[50]))
        vm.toggleMessage(ids[50])
        assertEquals(MAX_REPORT_MESSAGES, vm.state.value.selected.size)
        assertTrue(ids[50] in vm.state.value.selected)
    }

    @Test
    fun note_isCappedAtTheLimit() = runTest {
        val vm = newViewModel()
        vm.onNoteChange("x".repeat(MAX_REPORT_NOTE_LENGTH + 20))
        assertEquals(MAX_REPORT_NOTE_LENGTH, vm.state.value.note.length)
    }

    @Test
    fun send_reportsTheReasonTrimmedNoteAndPickedMessages() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val picked = vm.state.value.messages!!.take(2).map { it.messageId }
        vm.onReasonChange(ReportReason.HARASSMENT)
        vm.onNoteChange("  Rude  ")
        picked.forEach(vm::toggleMessage)
        vm.send()
        advanceUntilIdle()

        val report = fake.reports.single()
        assertEquals(DemoData.lukas.userId, report.userId)
        assertEquals(ReportReason.HARASSMENT, report.reason)
        assertEquals("Rude", report.note)
        assertEquals(picked.toSet(), report.messageIds.toSet())
        assertTrue(vm.state.value.sent)
        assertFalse(vm.state.value.blocked)
        assertTrue(fake.privacy().blockedUserIds.isEmpty())
    }

    @Test
    fun send_blankNote_sendsNoNote() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onReasonChange(ReportReason.OTHER)
        vm.onNoteChange("   ")
        vm.send()
        advanceUntilIdle()
        assertNull(fake.reports.single().note)
        assertEquals(emptyList<String>(), fake.reports.single().messageIds)
    }

    @Test
    fun alsoBlock_blocksAfterTheReport() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onReasonChange(ReportReason.SCAM)
        vm.onAlsoBlockChange(true)
        vm.send()
        advanceUntilIdle()
        assertEquals(1, fake.reports.size)
        assertTrue(DemoData.lukas.userId in fake.privacy().blockedUserIds)
        assertTrue(vm.state.value.sent)
        assertTrue(vm.state.value.blocked)
        assertFalse(vm.state.value.blockFailed)
    }

    @Test
    fun alsoBlock_failure_stillConfirmsTheReport() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.failNext("blockUser", GimException.Storage("disk full"))
        vm.onReasonChange(ReportReason.SCAM)
        vm.onAlsoBlockChange(true)
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.sent)
        assertFalse(vm.state.value.blocked)
        assertTrue(vm.state.value.blockFailed)
    }

    @Test
    fun reportFailure_showsTheError_doesNotBlock_andAllowsARetry() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        fake.failNext("reportUser", GimException.Network("offline"))
        vm.onReasonChange(ReportReason.SPAM)
        vm.onAlsoBlockChange(true)
        vm.send()
        advanceUntilIdle()
        assertEquals(ReportEvent.ShowError(ContactsError.Network), vm.events.first())
        assertFalse(vm.state.value.sent)
        assertFalse(vm.state.value.sending)
        assertTrue(vm.state.value.canSend)
        assertTrue(fake.privacy().blockedUserIds.isEmpty())

        fake.failNext("reportUser", GimException.Internal("boom"))
        vm.send()
        advanceUntilIdle()
        assertEquals(ReportEvent.ShowError(ContactsError.ChatActionFailed(ChatAction.REPORT)), vm.events.first())

        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.sent)
    }

    private companion object {
        const val KEEP = "https://keep.example.eu"
    }
}
