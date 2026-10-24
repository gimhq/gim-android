package im.g.apps.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.bridge.Timeline
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.feature.chat.timeline.ChatEvent
import im.g.apps.feature.chat.timeline.DateSeparatorRow
import im.g.apps.feature.chat.timeline.MediaPathProvider
import im.g.apps.feature.chat.timeline.MessageRow
import im.g.apps.feature.chat.timeline.SystemRow
import im.g.apps.feature.chat.timeline.TimelineRowUi
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Europe/Athens")
    private var now = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val clock = { now }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.demoClient(pageSize: Int = FakeGimClient.DEFAULT_PAGE_SIZE) =
        FakeGimClient.withDemoData(backgroundScope, clock, zone, outboxDelays = null, pageSize = pageSize)

    private fun TestScope.viewModel(client: GimClient, chatId: String, locale: Locale = Locale.UK): ChatViewModel =
        ChatViewModel(client, chatId, mediaPaths(), avatarDir, clock, zone, dispatcher, locale).also { runCurrent() }

    private val avatarDir by lazy { tmp.newFolder("avatars") }

    private fun mediaPaths(): MediaPathProvider {
        val mediaDir = tmp.newFolder()
        return object : MediaPathProvider {
            override fun thumbnailPath(media: MediaRef) = File(mediaDir, "${media.messageId}.thumb").path
            override fun mediaPath(media: MediaRef, fileName: String?) =
                File(mediaDir, fileName ?: media.messageId).path
        }
    }

    private val ChatViewModel.rows get() = uiState.value.items
    private val ChatViewModel.messages get() = rows.filterIsInstance<MessageRow>()
    private fun ChatViewModel.message(body: String) = messages.single { it.body == body }

    @Test
    fun dateSeparators_labelDaysAboveTheirMessages() = test {
        val vm = viewModel(demoClient(), DemoData.DIRECT_CHAT_ID)

        val separators = vm.rows.withIndex().filter { it.value is DateSeparatorRow }
        val labels = separators.map { (it.value as DateSeparatorRow).label }
        // Two days before "today" (2026-10-14): the weekday.
        assertEquals(
            listOf(UiText.Res(R.string.chat_today), UiText.Res(R.string.chat_yesterday), UiText.Raw("Monday")),
            labels,
        )
        // Newest first: every row before the "Today" separator is a message from today.
        val todayIndex = separators.first().index
        assertTrue(todayIndex > 0)
        assertTrue(vm.rows.take(todayIndex).all { it is MessageRow })
        assertTrue(separators.all { it.value.key.startsWith("date-") })
        assertTrue(vm.rows.last() is DateSeparatorRow)
    }

    @Test
    fun messageTimes_followTheLocaleTheViewModelWasCreatedWith() = test {
        val client = demoClient()
        val us = viewModel(client, DemoData.DIRECT_CHAT_ID, Locale.US).messages.map { it.time }
        val zh = viewModel(client, DemoData.DIRECT_CHAT_ID, Locale.SIMPLIFIED_CHINESE).messages.map { it.time }

        assertTrue(us.isNotEmpty())
        assertTrue(us.all { it.endsWith("AM") || it.endsWith("PM") })
        assertTrue(zh.all { it.startsWith("上午") || it.startsWith("下午") })
    }

    @Test
    fun grouping_showsSenderOnlyOnFirstOfRunInGroups() = test {
        val client = FakeGimClient(backgroundScope, clock, zone, outboxDelays = null)
        client.createIdentity("Sofia Costa")
        client.claimHandle("sofia")
        val ana = UserProfile("u-ana", "ana", "Ana Kovač")
        client.addDirectoryUser(ana)
        val chatId = client.createGroupChat("Crew", listOf(ana.userId))
        client.simulateIncoming(chatId, "first", ana.userId)
        now += 60_000
        client.simulateIncoming(chatId, "second", ana.userId)
        now += 10 * 60_000
        client.simulateIncoming(chatId, "third", ana.userId)
        client.sendText(chatId, "mine", null)

        val vm = viewModel(client, chatId)

        val first = vm.message("first")
        val second = vm.message("second")
        val third = vm.message("third")
        val mine = vm.message("mine")
        assertEquals("Ana Kovač", first.senderName)
        assertTrue(first.groupedWithNext)
        assertFalse(first.groupedWithPrevious)
        assertNull(second.senderName)
        assertTrue(second.groupedWithPrevious)
        assertFalse(second.groupedWithNext)
        assertEquals("Ana Kovač", third.senderName)
        assertFalse(third.groupedWithPrevious)
        assertNull(mine.senderName)
        assertTrue(mine.outgoing)
        assertEquals("12:11", third.time)
        assertTrue(vm.rows.any { it is SystemRow && it.text == UiText.Res(R.string.chat_member_you_added, persistentListOf("Ana Kovač")) })
        assertEquals(mine, vm.rows.first())
    }

    @Test
    fun directChat_hasNoSenderNames() = test {
        val vm = viewModel(demoClient(), DemoData.DIRECT_CHAT_ID)

        assertTrue(vm.messages.all { it.senderName == null })
        assertEquals("Lukas Berger", vm.uiState.value.header.title)
        assertFalse(vm.uiState.value.header.isGroup)
        assertNull(vm.uiState.value.header.subtitle)
    }

    @Test
    fun header_directChat_showsThePeerPhotoOnlyWhileThePeerHasOne() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        assertNull(vm.uiState.value.header.avatarPath)

        client.addDirectoryUser(DemoData.lukas.copy(hasAvatar = true))
        client.simulateIncoming(DemoData.DIRECT_CHAT_ID, "New photo", from = DemoData.lukas.userId)
        advanceUntilIdle()
        val path = vm.uiState.value.header.avatarPath
        assertEquals(File(avatarDir, DemoData.lukas.userId).path, path)
        assertTrue(File(path!!).isFile)

        client.addDirectoryUser(DemoData.lukas.copy(hasAvatar = false))
        client.simulateIncoming(DemoData.DIRECT_CHAT_ID, "Removed it", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertNull(vm.uiState.value.header.avatarPath)
    }

    @Test
    fun header_group_showsItsAvatarFromTheAvatarDir() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        advanceUntilIdle()
        assertNull(vm.uiState.value.header.avatarPath)

        val avatar = tmp.newFile("crew.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        client.setGroupInfo(DemoData.GROUP_CHAT_ID, null, avatar.path)
        advanceUntilIdle()

        val path = vm.uiState.value.header.avatarPath
        assertTrue(path != null && File(path).isFile)
        assertEquals(avatarDir.canonicalPath, File(path!!).canonicalFile.parent)
        assertTrue(File(path).name.startsWith("chat-${DemoData.GROUP_CHAT_ID}-"))
    }

    @Test
    fun blockedDirectChat_isBlocked_untilUnblocked() = test {
        val client = demoClient()
        client.blockUser(DemoData.lukas.userId)
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.blocked)

        vm.unblock()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.blocked)
        assertFalse(DemoData.lukas.userId in client.privacy().blockedUserIds)
    }

    @Test
    fun unblockFailure_keepsTheChatBlocked_andShowsAnError() = test {
        val client = demoClient()
        client.blockUser(DemoData.lukas.userId)
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        advanceUntilIdle()
        client.failNext("unblockUser", GimException.Network("offline"))

        vm.unblock()

        assertEquals(ChatEvent.Error(UiText.Res(R.string.chat_error_unblock)), vm.events.first())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.blocked)
        assertTrue(DemoData.lukas.userId in client.privacy().blockedUserIds)
    }

    @Test
    fun groupChat_isNeverBlocked() = test {
        val client = demoClient()
        client.blockUser(DemoData.lukas.userId)
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.blocked)
    }

    @Test
    fun update_keepsKeysStableAndReusesUnchangedRows() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        val before = vm.rows

        // A different sender than the newest message, so no existing run changes.
        client.simulateIncoming(DemoData.GROUP_CHAT_ID, "Ahoy!", DemoData.lukas.userId)
        runCurrent()

        val after = vm.rows
        assertEquals(before.size + 1, after.size)
        assertEquals("Ahoy!", (after.first() as MessageRow).body)
        assertEquals(before.map(TimelineRowUi::key), after.drop(1).map(TimelineRowUi::key))
        before.zip(after.drop(1)).forEach { (old, new) -> assertSame(old, new) }
    }

    @Test
    fun loadOlder_isSingleFlightAndStopsAtStart() = test {
        val gate = CompletableDeferred<Unit>()
        val client = CountingClient(demoClient(), gate)
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        val initial = vm.rows.size

        vm.loadOlder()
        vm.loadOlder()
        runCurrent()
        assertTrue(vm.uiState.value.isLoadingOlder)
        assertEquals(1, client.paginateCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoadingOlder)
        assertFalse(vm.uiState.value.hasMoreOlder)
        assertTrue(vm.rows.size > initial)
        assertEquals(ChatViewModel.PAGE_SIZE, client.lastPageSize)

        vm.loadOlder()
        runCurrent()
        assertEquals(1, client.paginateCalls)
    }

    @Test
    fun onVisible_debouncesMarkReadToNewestInbound() = test {
        val client = CountingClient(demoClient())
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        val inbound = vm.messages.filter { !it.outgoing }.map { it.key }

        vm.onVisible(inbound[2])
        advanceTimeBy(200)
        vm.onVisible(inbound[1])
        advanceTimeBy(200)
        vm.onVisible(inbound[0])
        advanceTimeBy(ChatViewModel.MARK_READ_DEBOUNCE_MS - 1)
        assertEquals(emptyList<String>(), client.markReads)

        advanceTimeBy(2)
        assertEquals(listOf(inbound[0]), client.markReads)
        runCurrent()
        assertEquals(0, vm.uiState.value.unreadCount)

        // Nothing is unread any more.
        vm.onVisible(inbound[0])
        advanceUntilIdle()
        assertEquals(1, client.markReads.size)
    }

    @Test
    fun onVisible_skipsChatsWithoutUnread() = test {
        val client = CountingClient(demoClient())
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)

        vm.onVisible(vm.messages.first().key)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), client.markReads)
    }

    @Test
    fun typing_updatesLabelAndSubtitle() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.GROUP_CHAT_ID)
        assertEquals(UiText.Plural(R.plurals.chat_members, 4), vm.uiState.value.header.subtitle)

        client.simulateTyping(DemoData.GROUP_CHAT_ID, DemoData.ana.userId, true)
        runCurrent()
        val one = UiText.Res(R.string.chat_typing_one, persistentListOf("Ana Kovač"))
        assertEquals(one, vm.uiState.value.typingLabel)
        assertEquals(one, vm.uiState.value.header.subtitle)

        client.simulateTyping(DemoData.GROUP_CHAT_ID, DemoData.nikos.userId, true)
        runCurrent()
        assertEquals(
            UiText.Res(R.string.chat_typing_two, persistentListOf("Ana Kovač", "Nikos Papadakis")),
            vm.uiState.value.typingLabel,
        )

        client.simulateTyping(DemoData.GROUP_CHAT_ID, DemoData.ana.userId, false)
        client.simulateTyping(DemoData.GROUP_CHAT_ID, DemoData.nikos.userId, false)
        runCurrent()
        assertNull(vm.uiState.value.typingLabel)
        assertEquals(UiText.Plural(R.plurals.chat_members, 4), vm.uiState.value.header.subtitle)
    }

    @Test
    fun typing_inDirectChatShowsTypingSubtitle() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)

        client.simulateTyping(DemoData.DIRECT_CHAT_ID, DemoData.lukas.userId, true)
        runCurrent()

        assertEquals(UiText.Res(R.string.chat_typing), vm.uiState.value.header.subtitle)
    }

    @Test
    fun ticks_progressFromPendingToRead() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)

        client.sendText(DemoData.DIRECT_CHAT_ID, "On my way", null)
        runCurrent()
        assertEquals(TickState.PENDING, vm.message("On my way").tick)
        val seen = mutableListOf<TickState?>()
        repeat(3) {
            client.advanceOutbox()
            runCurrent()
            seen += vm.message("On my way").tick
        }
        assertEquals(listOf(TickState.SENT, TickState.DELIVERED, TickState.READ), seen)
    }

    @Test
    fun failedMessage_retryRequeues() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        client.failNextSend()
        client.sendText(DemoData.DIRECT_CHAT_ID, "Lost", null)
        client.advanceOutbox()
        runCurrent()
        val failed = vm.message("Lost")
        assertTrue(failed.failed)

        vm.retry(failed.key)
        runCurrent()

        assertEquals(TickState.PENDING, vm.message("Lost").tick)
    }

    @Test
    fun failedMessage_retryHiddenForRefusedCauses_keptForOthers() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        for (cause in SendFailure.entries) {
            client.failNextSend(cause = cause)
            client.sendText(DemoData.DIRECT_CHAT_ID, "Lost $cause", null)
        }
        client.advanceOutbox()
        runCurrent()

        assertTrue(SendFailure.entries.all { vm.message("Lost $it").failed })
        val retryable = SendFailure.entries.associateWith { vm.message("Lost $it").retryable }
        assertEquals(
            mapOf(
                SendFailure.USER_SUSPENDED to false,
                SendFailure.USER_NOT_ADMITTED to false,
                SendFailure.NOT_ADMITTED_BY_PEER to false,
                SendFailure.REJECTED to true,
                SendFailure.OTHER to true,
            ),
            retryable,
        )
    }

    @Test
    fun backgroundOutboxFailureSuspended_offersNoRetry_withoutARetryAttempt() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        client.failNextSend("user_suspended", SendFailure.USER_SUSPENDED)
        client.sendText(DemoData.DIRECT_CHAT_ID, "Lost", null)
        client.advanceOutbox()
        runCurrent()

        val lost = vm.message("Lost")
        assertTrue(lost.failed)
        assertFalse(lost.retryable)
        assertEquals(SendFailure.USER_SUSPENDED, lost.sendFailure)

        // A tap does not reach retrySend: the message stays failed and no error is reported.
        vm.retry(lost.key)
        runCurrent()
        assertEquals(TickState.FAILED, vm.message("Lost").tick)
    }

    @Test
    fun failedMessageNotAdmittedByPeer_failsAtOnce_otherChatsUnaffected() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        client.simulateNotAdmittedByPeer(DemoData.DIRECT_CHAT_ID)
        client.sendText(DemoData.DIRECT_CHAT_ID, "Refused", null)
        runCurrent()

        val refused = vm.message("Refused")
        assertTrue(refused.failed)
        assertEquals(SendFailure.NOT_ADMITTED_BY_PEER, refused.sendFailure)
        assertFalse(refused.retryable)
        val group = viewModel(client, DemoData.GROUP_CHAT_ID)
        client.sendText(DemoData.GROUP_CHAT_ID, "Fine", null)
        client.advanceOutbox()
        runCurrent()
        assertEquals(TickState.SENT, group.message("Fine").tick)
    }

    @Test
    fun deletedAndEdited_mapToRenderModels() = test {
        val vm = viewModel(demoClient(), DemoData.DIRECT_CHAT_ID)

        val edited = vm.message("Thanks, reading it tonight.")
        assertTrue(edited.edited)
        assertFalse(edited.deleted)
        assertEquals("Lukas Berger", edited.replyPreview?.senderName)
        assertEquals(UiText.Res(R.string.chat_reply_file, persistentListOf("survey-2026.pdf")), edited.replyPreview?.text)

        val deleted = vm.messages.single { it.deleted }
        assertNull(deleted.body)
        assertFalse(deleted.edited)
        assertEquals("message-deleted", deleted.contentType)

        val reacted = vm.message("Great, he knows the Ionian well.")
        assertEquals("👍", reacted.reactions.single().emoji)
        assertEquals(TickState.READ, reacted.tick)
    }

    @Test
    fun images_resolvePathLazily() = test {
        val vm = viewModel(demoClient(), DemoData.DIRECT_CHAT_ID)
        val image = vm.messages.single { it.image != null }
        assertNull(image.image?.path)
        assertEquals(1600f / 1200f, image.image!!.aspectRatio, 0.001f)
        assertEquals("The boat at Porto Antico", image.body)

        vm.onImageNeeded(image.key)
        advanceUntilIdle()

        val path = vm.messages.single { it.key == image.key }.image?.path
        assertNotNull(path)
        assertTrue(path!!.endsWith(image.key))
        assertTrue(File(path).isFile)
    }

    @Test
    fun openFile_emitsPathAndMime() = test {
        val vm = viewModel(demoClient(), DemoData.DIRECT_CHAT_ID)
        val file = vm.messages.single { it.file != null }
        assertEquals("survey-2026.pdf", file.file?.name)
        assertEquals("PDF", file.file?.typeLabel)
        assertEquals("471 KB", file.file?.sizeLabel)

        vm.onOpenFile(file.key)
        val event = vm.events.first() as ChatEvent.OpenFile

        assertEquals("application/pdf", event.mime)
        assertTrue(event.path.endsWith("survey-2026.pdf"))
        assertTrue(File(event.path).isFile)
    }

    @Test
    fun retryFailure_emitsErrorEvent() = test {
        val client = demoClient()
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)

        // Not in Failed state, so the client rejects the retry.
        vm.retry(vm.message("See you then!").key)

        assertEquals(ChatEvent.Error(UiText.Res(R.string.composer_error_retry)), vm.events.first())
    }

    /** `fetchMedia` fails with each of [failures] in turn, then succeeds. */
    private class FlakyMediaClient(private val delegate: FakeGimClient, vararg failures: GimException) : GimClient by delegate {
        private val pending = ArrayDeque(failures.toList())
        var fetches = 0

        override suspend fun fetchMedia(chatId: String, messageId: String, destPath: String): String {
            fetches++
            pending.removeFirstOrNull()?.let { throw it }
            return delegate.fetchMedia(chatId, messageId, destPath)
        }
    }

    @Test
    fun imageFromAnUnreachableHub_offersARetryThatRefetches() = test {
        val client = FlakyMediaClient(demoClient(), GimException.RetryLater(503, 20, "peer_unavailable"))
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        val image = vm.messages.single { it.image != null }
        assertFalse(image.mediaUnavailable)

        vm.onImageNeeded(image.key)
        advanceUntilIdle()
        assertTrue(vm.messages.single { it.key == image.key }.mediaUnavailable)

        vm.retryMedia(image.key)
        advanceUntilIdle()
        val retried = vm.messages.single { it.key == image.key }
        assertFalse(retried.mediaUnavailable)
        assertTrue(File(retried.image!!.path!!).isFile)
        assertEquals(2, client.fetches)
    }

    @Test
    fun fileRefusedByTheUploadersHub_offersARetry() = test {
        val client = FlakyMediaClient(demoClient(), GimException.Server(502, "peer_refused"))
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        val file = vm.messages.single { it.file != null }

        vm.onOpenFile(file.key)
        assertEquals(ChatEvent.Error(UiText.Res(R.string.chat_error_media_unavailable)), vm.events.first())
        advanceUntilIdle()
        assertTrue(vm.messages.single { it.key == file.key }.mediaUnavailable)

        vm.retryMedia(file.key)
        assertTrue(vm.events.first() is ChatEvent.OpenFile)
        advanceUntilIdle()
        assertFalse(vm.messages.single { it.key == file.key }.mediaUnavailable)
    }

    @Test
    fun openFile_retryLater_showsTheWait_otherFailuresStayGeneric() = test {
        val client = FlakyMediaClient(
            demoClient(),
            GimException.RetryLater(503, 7, "peer_unavailable"),
            GimException.NotFound("blob"),
        )
        val vm = viewModel(client, DemoData.DIRECT_CHAT_ID)
        val file = vm.messages.single { it.file != null }

        vm.onOpenFile(file.key)
        assertEquals(
            ChatEvent.Error(UiText.Plural(R.plurals.chat_error_retry_later, 7)),
            vm.events.first(),
        )
        vm.retryMedia(file.key)
        assertEquals(ChatEvent.Error(UiText.Res(R.string.chat_error_open_file)), vm.events.first())
        advanceUntilIdle()
        assertFalse(vm.messages.single { it.key == file.key }.mediaUnavailable)
    }

    @Test
    fun uiState_isWrittenOnMainThread_whileMappingRunsOnDefaultDispatcher() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        val default = Executors.newSingleThreadExecutor { Thread(it, "test-default") }.asCoroutineDispatcher()
        val clientScope = CoroutineScope(SupervisorJob() + default)
        val store = ViewModelStore()
        var vmJob: Job? = null
        Dispatchers.setMain(main)
        try {
            runBlocking {
                val client = FakeGimClient.withDemoData(clientScope, clock, zone, outboxDelays = null)
                val vm = store.own(
                    ChatViewModel(client, DemoData.DIRECT_CHAT_ID, mediaPaths(), avatarDir, clock, zone, default, Locale.UK),
                )
                vmJob = vm.viewModelScope.coroutineContext.job
                val writers = Collections.synchronizedList(mutableListOf<String>())
                val pingShown = CompletableDeferred<Unit>()
                val suspended = AtomicBoolean(false)
                // Once suspended, an unconfined collector resumes on the thread that wrote the StateFlow.
                val collector = launch(Dispatchers.Unconfined) {
                    vm.uiState.collect { state ->
                        if (!suspended.get()) return@collect
                        writers += Thread.currentThread().name.substringBefore(" @")
                        if (state.items.any { (it as? MessageRow)?.body == "ping" }) pingShown.complete(Unit)
                    }
                }
                suspended.set(true)
                client.simulateIncoming(DemoData.DIRECT_CHAT_ID, "ping", from = DemoData.lukas.userId)
                withTimeout(10_000) { pingShown.await() }
                collector.cancel()
                assertEquals(writers.map { "test-main" }, writers.toList())
            }
        } finally {
            store.clear()
            // Let the cancelled viewModelScope finish on this Main before tearDown resets it.
            runBlocking { withTimeout(10_000) { vmJob?.join() } }
            clientScope.cancel()
            main.close()
            default.close()
        }
    }

    /** Registers [vm] in this store so [ViewModelStore.clear] cancels its viewModelScope. */
    private inline fun <reified T : ViewModel> ViewModelStore.own(vm: T): T =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
            },
        )[T::class.java]

    /** Records markRead and pagination calls; pagination waits for [gate] when given. */
    private class CountingClient(
        private val delegate: FakeGimClient,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : GimClient by delegate {
        val markReads = mutableListOf<String>()
        var paginateCalls = 0
        var lastPageSize = 0

        override suspend fun markRead(chatId: String, upToMessageId: String) {
            markReads += upToMessageId
            delegate.markRead(chatId, upToMessageId)
        }

        override suspend fun timeline(chatId: String): Timeline {
            val timeline = delegate.timeline(chatId)
            return object : Timeline by timeline {
                override suspend fun paginateBackwards(count: Int): Boolean {
                    paginateCalls++
                    lastPageSize = count
                    gate?.await()
                    return timeline.paginateBackwards(count)
                }
            }
        }
    }
}
