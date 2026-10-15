package im.g.apps.feature.chat.composer

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.feature.chat.R
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val chatId = DemoData.DIRECT_CHAT_ID

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.fake() = FakeGimClient.withDemoData(backgroundScope, { testScheduler.currentTime }, outboxDelays = null)

    private fun TestScope.viewModel(client: GimClient, copier: UriCopier = FakeUriCopier(ByteArray(0))) =
        ComposerViewModel(client, chatId, copier, { testScheduler.currentTime }, dispatcher, dispatcher)
            .also { runCurrent() }

    private suspend fun GimClient.messages(): List<MessageItem> =
        timeline(chatId).itemsFlow().first().mapNotNull { (it as? TimelineItem.Message)?.item }

    private suspend fun GimClient.message(text: String): MessageItem =
        messages().single { (it.content as? MessageContent.Text)?.body == text }

    @Test
    fun send_textClearsDraftAndQueuesMessage() = test {
        val client = fake()
        val vm = viewModel(client)

        vm.onDraftChange("  Fair winds  ")
        assertTrue(vm.uiState.value.canSend)
        vm.send()
        runCurrent()

        assertEquals("", vm.uiState.value.draft)
        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
        val sent = client.messages().last()
        assertEquals(MessageContent.Text("Fair winds"), sent.content)
        assertTrue(sent.isOwn)
        assertNull(sent.replyTo)
    }

    @Test
    fun send_blankDraftIsNoOp() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val vm = viewModel(client)

        vm.onDraftChange("   ")
        assertFalse(vm.uiState.value.canSend)
        vm.send()
        runCurrent()

        assertEquals(emptyList<String>(), client.sentTexts)
        assertEquals("   ", vm.uiState.value.draft)
    }

    @Test
    fun reply_sendsWithReplyTo() = test {
        val client = fake()
        val vm = viewModel(client)
        val target = client.message("Good call. I'll check the currents.")

        vm.onMessageLongPress(target.messageId)
        assertEquals(target.messageId, vm.uiState.value.actions?.messageId)
        vm.reply(target.messageId)
        assertNull(vm.uiState.value.actions)
        assertEquals(
            ComposerMode.Replying(target.messageId, "Lukas Berger", UiText.Raw("Good call. I'll check the currents.")),
            vm.uiState.value.mode,
        )

        vm.onDraftChange("Thanks!")
        vm.send()
        runCurrent()

        val sent = client.messages().last()
        assertEquals(target.messageId, sent.replyTo?.messageId)
        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
    }

    @Test
    fun cancelMode_closesReplyAndKeepsDraft() = test {
        val client = fake()
        val vm = viewModel(client)
        val target = client.message("Good call. I'll check the currents.")
        vm.onDraftChange("draft")
        vm.reply(target.messageId)

        vm.cancelMode()

        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
        assertEquals("draft", vm.uiState.value.draft)
    }

    @Test
    fun edit_callsEditMessage() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val vm = viewModel(client)
        val own = client.message("Thursday 18:00 CET works.")

        vm.edit(own.messageId)
        assertEquals(ComposerMode.Editing(own.messageId, "Thursday 18:00 CET works."), vm.uiState.value.mode)
        assertEquals("Thursday 18:00 CET works.", vm.uiState.value.draft)

        vm.onDraftChange("Thursday 19:00 CET works.")
        vm.send()
        runCurrent()

        assertEquals(listOf(own.messageId to "Thursday 19:00 CET works."), client.edits)
        assertEquals(emptyList<String>(), client.sentTexts)
        val edited = client.messages().single { it.messageId == own.messageId }
        assertTrue(edited.edited)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
        // Editing never sends typing notifications.
        assertEquals(emptyList<Boolean>(), client.typing.map { it.second })
    }

    @Test
    fun edit_ignoredForOthersMessages() = test {
        val client = fake()
        val vm = viewModel(client)
        val other = client.message("Good call. I'll check the currents.")

        vm.edit(other.messageId)

        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
    }

    @Test
    fun delete_onlyOwnAfterConfirmation() = test {
        val client = fake()
        val vm = viewModel(client)
        val other = client.message("Good call. I'll check the currents.")
        val own = client.message("Thursday 18:00 CET works.")

        vm.requestDelete(other.messageId)
        assertNull(vm.uiState.value.confirmDeleteId)

        vm.requestDelete(own.messageId)
        assertEquals(own.messageId, vm.uiState.value.confirmDeleteId)
        vm.cancelDelete()
        runCurrent()
        assertEquals(MessageContent.Text("Thursday 18:00 CET works."), client.messages().single { it.messageId == own.messageId }.content)

        vm.requestDelete(own.messageId)
        vm.confirmDelete()
        runCurrent()

        assertNull(vm.uiState.value.confirmDeleteId)
        assertEquals(MessageContent.Deleted, client.messages().single { it.messageId == own.messageId }.content)
        assertEquals(MessageContent.Text("Good call. I'll check the currents."), client.messages().single { it.messageId == other.messageId }.content)
    }

    @Test
    fun reaction_toggles() = test {
        val client = fake()
        val vm = viewModel(client)
        val target = client.message("Good call. I'll check the currents.")

        vm.toggleReaction(target.messageId, "👍")
        runCurrent()
        assertEquals(listOf(ReactionGroup("👍", 1, own = true)), client.messages().single { it.messageId == target.messageId }.reactions)

        vm.onMessageLongPress(target.messageId)
        assertEquals(persistentSetOf("👍"), vm.uiState.value.actions?.ownReactions)
        vm.toggleReaction(target.messageId, "👍")
        runCurrent()
        assertEquals(emptyList<ReactionGroup>(), client.messages().single { it.messageId == target.messageId }.reactions)
    }

    @Test
    fun typing_throttledAndStopsWhenIdle() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val vm = viewModel(client)

        vm.onDraftChange("H")
        runCurrent()
        advanceTimeBy(1_000)
        vm.onDraftChange("He")
        advanceTimeBy(2_000)
        vm.onDraftChange("Hel")
        runCurrent()
        assertEquals(listOf(0L to true), client.typing)

        advanceTimeBy(1_500) // 4.5 s after the first notification
        vm.onDraftChange("Hell")
        runCurrent()
        assertEquals(listOf(0L to true, 4_500L to true), client.typing)

        advanceTimeBy(4_999)
        assertEquals(2, client.typing.size)
        advanceTimeBy(2)
        assertEquals(listOf(0L to true, 4_500L to true, 9_500L to false), client.typing)
    }

    @Test
    fun typing_stopsOnSendAndOnClear() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val vm = viewModel(client)

        vm.onDraftChange("Hi")
        vm.send()
        runCurrent()
        assertEquals(listOf(true, false), client.typing.map { it.second })

        vm.onDraftChange("x")
        vm.onDraftChange("")
        runCurrent()
        assertEquals(listOf(true, false, true, false), client.typing.map { it.second })

        // No stray idle notification afterwards.
        advanceTimeBy(10_000)
        assertEquals(4, client.typing.size)
    }

    @Test
    fun media_sendsWithCaptionAndReplyThenDeletesCopy() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val copier = FakeUriCopier(ByteArray(2_048) { it.toByte() }, mime = "application/pdf")
        val vm = viewModel(client, copier)
        val target = client.message("Good call. I'll check the currents.")
        vm.reply(target.messageId)
        vm.onDraftChange(" Route notes ")

        vm.onAttachmentPicked("content://picker/42")
        runCurrent()

        val call = client.mediaSends.single()
        assertEquals("content://picker/42", copier.requested.single())
        assertEquals("notes.pdf", File(call.path).name)
        assertEquals("application/pdf", call.mime)
        assertEquals("Route notes", call.caption)
        assertEquals(target.messageId, call.replyTo)
        assertFalse(File(call.path).exists())
        assertFalse(File(call.path).parentFile!!.exists())
        assertEquals("", vm.uiState.value.draft)
        assertEquals(ComposerMode.Normal, vm.uiState.value.mode)
        assertFalse(vm.uiState.value.isAttaching)
        val sent = client.messages().last().content as MessageContent.File
        assertEquals("notes.pdf", sent.fileName)
        assertEquals("Route notes", sent.caption)
    }

    @Test
    fun media_tooLargeEmitsErrorAndKeepsDraft() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime })
        val vm = viewModel(client, FakeUriCopier(ByteArray(0), tooLarge = true))
        vm.onDraftChange("caption")

        vm.onAttachmentPicked("content://picker/huge")
        runCurrent()

        assertEquals(ComposerEvent.Error(UiText.Res(R.string.composer_error_too_large)), vm.events.first())
        assertEquals(emptyList<MediaSend>(), client.mediaSends)
        assertEquals("caption", vm.uiState.value.draft)
    }

    @Test
    fun media_unreadableEmitsError() = test {
        val vm = viewModel(fake(), FakeUriCopier(ByteArray(0), failure = IOException("gone")))

        vm.onAttachmentPicked("content://picker/gone")
        runCurrent()

        assertEquals(ComposerEvent.Error(UiText.Res(R.string.composer_error_attach)), vm.events.first())
    }

    @Test
    fun media_copyDeletedWhenClientRejects() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime }, failMedia = true)
        val copier = FakeUriCopier(ByteArray(16))
        val vm = viewModel(client, copier)

        vm.onAttachmentPicked("content://picker/1")
        runCurrent()

        assertEquals(ComposerEvent.Error(UiText.Res(R.string.composer_error_send)), vm.events.first())
        assertFalse(File(client.mediaSends.single().path).exists())
    }

    @Test
    fun sendFailure_emitsSnackbarEvent() = test {
        val client = RecordingClient(fake(), { testScheduler.currentTime }, failText = true)
        val vm = viewModel(client)

        vm.onDraftChange("Hello")
        vm.send()
        runCurrent()

        assertEquals(ComposerEvent.Error(UiText.Res(R.string.composer_error_send)), vm.events.first())
    }

    @Test
    fun actions_availabilityMatrix() = test {
        val client = fake()
        val vm = viewModel(client)
        val messages = client.messages()
        val ownText = client.message("Thursday 18:00 CET works.")
        val otherText = client.message("Good call. I'll check the currents.")
        val otherImage = messages.single { it.content is MessageContent.Image }
        val deleted = messages.single { it.content == MessageContent.Deleted }
        client.failNextSend()
        val failedId = client.sendText(chatId, "Lost", null)
        client.advanceOutbox()
        runCurrent()
        val failed = client.messages().single { it.messageId == failedId }
        assertTrue(failed.sendState is SendState.Failed)

        fun actions(message: MessageItem): MessageActionsUi {
            vm.onMessageLongPress(message.messageId)
            return vm.uiState.value.actions!!.also { vm.dismissActions() }
        }

        with(actions(ownText)) {
            assertTrue(canReply); assertEquals("Thursday 18:00 CET works.", copyText)
            assertTrue(canEdit); assertTrue(canDelete); assertTrue(canReact); assertFalse(canRetry)
        }
        with(actions(otherText)) {
            assertTrue(canReply); assertEquals("Good call. I'll check the currents.", copyText)
            assertFalse(canEdit); assertFalse(canDelete); assertTrue(canReact); assertFalse(canRetry)
        }
        with(actions(otherImage)) {
            assertEquals("The boat at Porto Antico", copyText)
            assertFalse(canEdit); assertFalse(canDelete)
            assertEquals(persistentSetOf("⛵"), ownReactions)
        }
        with(actions(deleted)) {
            assertFalse(canReply); assertNull(copyText); assertFalse(canEdit)
            assertFalse(canDelete); assertFalse(canReact); assertFalse(canRetry)
        }
        with(actions(failed)) {
            assertTrue(canRetry); assertTrue(canEdit); assertTrue(canDelete)
        }

        vm.retry(failedId)
        runCurrent()
        assertEquals(SendState.Pending, client.messages().single { it.messageId == failedId }.sendState)
    }

    @Test
    fun cancelSend_removesFailedMessage() = test {
        val client = fake()
        val vm = viewModel(client)
        client.failNextSend()
        val failedId = client.sendText(chatId, "Lost", null)
        client.advanceOutbox()
        runCurrent()

        vm.cancelSend(failedId)
        runCurrent()

        assertTrue(client.messages().none { it.messageId == failedId })
    }

    @Test
    fun sanitizeFileName_keepsOneSafeSegment() {
        assertEquals("a_b_c.pdf", sanitizeFileName("a/b\\c.pdf"))
        assertEquals("_passwd", sanitizeFileName("../passwd"))
        assertEquals("file", sanitizeFileName(null))
        assertEquals("file", sanitizeFileName(" .. "))
        val long = sanitizeFileName("x".repeat(300) + ".jpeg")
        assertEquals(128, long.length)
        assertTrue(long.endsWith(".jpeg"))
    }

    private data class MediaSend(val path: String, val mime: String, val caption: String?, val replyTo: String?)

    /** Records calls with virtual timestamps; optionally fails sends. */
    private class RecordingClient(
        private val delegate: FakeGimClient,
        private val clock: () -> Long,
        private val failText: Boolean = false,
        private val failMedia: Boolean = false,
    ) : GimClient by delegate {
        val sentTexts = mutableListOf<String>()
        val edits = mutableListOf<Pair<String, String>>()
        val typing = mutableListOf<Pair<Long, Boolean>>()
        val mediaSends = mutableListOf<MediaSend>()

        override suspend fun sendText(chatId: String, body: String, replyTo: String?): String {
            sentTexts += body
            if (failText) throw GimException.Network("offline")
            return delegate.sendText(chatId, body, replyTo)
        }

        override suspend fun editMessage(chatId: String, messageId: String, newBody: String) {
            edits += messageId to newBody
            delegate.editMessage(chatId, messageId, newBody)
        }

        override suspend fun setTyping(chatId: String, active: Boolean) {
            typing += clock() to active
            delegate.setTyping(chatId, active)
        }

        override suspend fun sendMedia(chatId: String, filePath: String, mime: String, caption: String?, replyTo: String?): String {
            mediaSends += MediaSend(filePath, mime, caption, replyTo)
            assertTrue("copy must exist during sendMedia", File(filePath).isFile)
            if (failMedia) throw GimException.Network("offline")
            return delegate.sendMedia(chatId, filePath, mime, caption, replyTo)
        }
    }

    /** In-memory picker content written to `outgoing/<uuid>/notes.pdf` under a temp folder. */
    private inner class FakeUriCopier(
        private val bytes: ByteArray,
        private val mime: String = "application/octet-stream",
        private val tooLarge: Boolean = false,
        private val failure: IOException? = null,
    ) : UriCopier {
        val requested = mutableListOf<String>()

        override fun copy(uri: String, maxBytes: Long): CopyResult {
            requested += uri
            failure?.let { throw it }
            if (tooLarge) return CopyResult.TooLarge
            val dir = File(tmp.root, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
            val file = File(dir, "notes.pdf").apply { writeBytes(bytes) }
            return CopyResult.Copied(file.path, mime)
        }
    }
}
