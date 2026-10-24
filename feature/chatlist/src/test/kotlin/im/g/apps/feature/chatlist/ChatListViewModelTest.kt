package im.g.apps.feature.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.designsystem.component.TickState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Athens")
    private val now = ZonedDateTime.of(2026, 7, 12, 10, 0, 0, 0, zone).toInstant()
    private val clock = Clock.fixed(now, zone)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val avatarDir by lazy { tmp.newFolder("avatars") }

    private fun TestScope.demo() = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { now.toEpochMilli() + testScheduler.currentTime },
        zone = zone,
        outboxDelays = null,
    )

    /** Clock whose instant tests move by hand. */
    private class MutableClock(var instant: Instant, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    }

    private fun TestScope.viewModel(
        client: GimClient,
        clock: Clock = this@ChatListViewModelTest.clock,
        ticker: Flow<Unit> = flowOf(Unit),
        locale: Locale = Locale.UK,
    ): ChatListViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val vm = ChatListViewModel(
            client = client,
            clock = clock,
            avatarDir = avatarDir,
            defaultDispatcher = dispatcher,
            locale = locale,
            ticker = ticker,
        )
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private fun ChatListViewModel.row(id: String) = uiState.value.rows.first { it.id == id }

    @Test
    fun initial_list_maps_demo_chats_most_recent_first() = runTest {
        val vm = viewModel(demo())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isEmpty)
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), state.rows.map { it.id })
        val group = state.rows[0]
        assertEquals("Aegean Crew", group.title)
        assertEquals(PreviewSender.Member("Nikos"), group.sender)
        assertEquals("Booked a table in Vourkari for the first night.", group.preview)
        assertEquals(ChatTimeLabel.Minutes(2), group.time)
        assertEquals(3, group.unreadCount)
        assertNull(group.avatarPath)
        val direct = state.rows[1]
        assertEquals(DemoData.lukas.displayName, direct.title)
        assertEquals(PreviewSender.You, direct.sender)
        assertEquals("See you then!", direct.preview)
        assertEquals(0, direct.unreadCount)
    }

    @Test
    fun row_times_follow_the_locale_the_view_model_was_created_with() = runTest {
        val client = demo()
        // Four days later the latest demo chat shows a short weekday.
        val later = Clock.fixed(now.plus(Duration.ofDays(4)), zone)
        val uk = viewModel(client, clock = later, locale = Locale.UK)
        val zh = viewModel(client, clock = later, locale = Locale.SIMPLIFIED_CHINESE)
        advanceUntilIdle()

        val ukDays = uk.uiState.value.rows.map { it.time }.filterIsInstance<ChatTimeLabel.Weekday>()
        val zhDays = zh.uiState.value.rows.map { it.time }.filterIsInstance<ChatTimeLabel.Weekday>()
        assertTrue(ukDays.isNotEmpty())
        assertTrue(ukDays.all { it.text.first().isLetter() && it.text.all { c -> c.code < 128 } })
        assertEquals(ukDays.size, zhDays.size)
        assertTrue(zhDays.all { it.text.startsWith("周") })
    }

    @Test
    fun rows_follow_the_chat_state() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(ChatState.ACTIVE, vm.row(GROUP_CHAT_ID).state)

        client.leaveChat(GROUP_CHAT_ID)
        advanceUntilIdle()
        assertEquals(ChatState.LEAVING, vm.row(GROUP_CHAT_ID).state)

        client.commitLeave(GROUP_CHAT_ID)
        advanceUntilIdle()
        assertEquals(ChatState.LEFT, vm.row(GROUP_CHAT_ID).state)
        assertEquals(ChatState.ACTIVE, vm.row(DIRECT_CHAT_ID).state)
    }

    @Test
    fun incoming_message_moves_chat_to_top_and_mark_read_clears_unread() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()

        val id = client.simulateIncoming(DIRECT_CHAT_ID, "Weather looks good", from = DemoData.lukas.userId)
        advanceUntilIdle()

        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
        val direct = vm.row(DIRECT_CHAT_ID)
        assertNull(direct.sender)
        assertEquals("Weather looks good", direct.preview)
        assertEquals(1, direct.unreadCount)

        client.simulateIncoming(DIRECT_CHAT_ID, "Call me", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertEquals(2, vm.row(DIRECT_CHAT_ID).unreadCount)

        client.markRead(DIRECT_CHAT_ID, id)
        advanceUntilIdle()
        assertEquals(1, vm.row(DIRECT_CHAT_ID).unreadCount)
    }

    @Test
    fun mark_read_up_to_last_message_clears_badge() = runTest {
        val client = demo()
        val vm = viewModel(client)
        val id = client.simulateIncoming(GROUP_CHAT_ID, "Last one", from = DemoData.ana.userId)
        advanceUntilIdle()
        assertEquals(4, vm.row(GROUP_CHAT_ID).unreadCount)
        assertEquals(PreviewSender.Member("Ana"), vm.row(GROUP_CHAT_ID).sender)

        client.markRead(GROUP_CHAT_ID, id)
        advanceUntilIdle()
        assertEquals(0, vm.row(GROUP_CHAT_ID).unreadCount)
    }

    @Test
    fun preview_formatting_per_content_type() = runTest {
        val client = demo()
        val vm = viewModel(client)

        client.sendMedia(GROUP_CHAT_ID, tmp.newFile("sunset.jpg").path, "image/jpeg", null, null)
        advanceUntilIdle()
        assertEquals(PreviewSender.You, vm.row(GROUP_CHAT_ID).sender)
        assertEquals("Photo", vm.row(GROUP_CHAT_ID).preview)

        val fileId = client.sendMedia(GROUP_CHAT_ID, tmp.newFile("report.pdf").path, "application/pdf", null, null)
        advanceUntilIdle()
        assertEquals("File: report.pdf", vm.row(GROUP_CHAT_ID).preview)

        client.deleteMessage(GROUP_CHAT_ID, fileId)
        advanceUntilIdle()
        assertEquals("Message deleted", vm.row(GROUP_CHAT_ID).preview)

        client.sendText(DIRECT_CHAT_ID, "On my way", null)
        advanceUntilIdle()
        assertEquals(PreviewSender.You, vm.row(DIRECT_CHAT_ID).sender)
        assertEquals("On my way", vm.row(DIRECT_CHAT_ID).preview)
    }

    @Test
    fun connection_banner_follows_connection_state() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(ConnectionBannerUi.WAITING_FOR_NETWORK, vm.uiState.value.connection)

        val expected = listOf(
            ConnectionState.CONNECTING to ConnectionBannerUi.CONNECTING,
            ConnectionState.SYNCING to ConnectionBannerUi.UPDATING,
            ConnectionState.CONNECTED to null,
            ConnectionState.OFFLINE to ConnectionBannerUi.WAITING_FOR_NETWORK,
        )
        for ((state, banner) in expected) {
            client.setConnection(state)
            advanceUntilIdle()
            assertEquals(banner, vm.uiState.value.connection)
        }
    }

    @Test
    fun empty_client_reports_empty_state() = runTest {
        val vm = viewModel(FakeGimClient(backgroundScope, outboxDelays = null))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun rows_are_sorted_even_if_the_client_delivers_them_unsorted() = runTest {
        val fake = demo()
        fake.setChatPinned(DIRECT_CHAT_ID, true)
        val reversing = object : GimClient by fake {
            override suspend fun chatList(listener: ChatListListener) = fake.chatList { diffs ->
                listener.onUpdate(diffs.map { if (it is ChatListDiff.Reset) ChatListDiff.Reset(it.values.reversed()) else it })
            }
        }
        val vm = viewModel(reversing)
        advanceUntilIdle()
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
    }

    @Test
    fun group_avatar_is_fetched_into_the_avatar_dir() = runTest {
        val client = demo()
        val vm = viewModel(client)
        val avatar = tmp.newFile("crew.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        client.setGroupInfo(GROUP_CHAT_ID, null, avatar.path)
        advanceUntilIdle()

        val path = vm.row(GROUP_CHAT_ID).avatarPath
        assertTrue(path != null && File(path).isFile)
        assertEquals(avatarDir.canonicalPath, File(path!!).canonicalFile.parent)
        assertNull(vm.row(DIRECT_CHAT_ID).avatarPath)
    }

    @Test
    fun direct_chat_shows_the_peer_photo_only_while_the_peer_has_one() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertNull(vm.row(DIRECT_CHAT_ID).avatarPath)

        client.addDirectoryUser(DemoData.lukas.copy(hasAvatar = true))
        client.simulateIncoming(DIRECT_CHAT_ID, "New photo", from = DemoData.lukas.userId)
        advanceUntilIdle()
        val path = vm.row(DIRECT_CHAT_ID).avatarPath
        assertEquals(File(avatarDir, DemoData.lukas.userId).path, path)
        assertTrue(File(path!!).isFile)

        client.addDirectoryUser(DemoData.lukas.copy(hasAvatar = false))
        client.simulateIncoming(DIRECT_CHAT_ID, "Removed it", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertNull(vm.row(DIRECT_CHAT_ID).avatarPath)
    }

    @Test
    fun own_photo_is_loaded_when_the_own_profile_has_one() = runTest {
        val client = demo()
        val vm = viewModel(client)
        vm.refreshOwnPhoto()
        advanceUntilIdle()
        assertNull(vm.uiState.value.ownPhotoPath)

        client.setAvatar(tmp.newFile("me.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }.path, "image/png")
        vm.refreshOwnPhoto()
        advanceUntilIdle()
        val path = vm.uiState.value.ownPhotoPath
        assertEquals(File(avatarDir, DemoData.session.userId).path, path)
        assertTrue(File(path!!).isFile)

        client.removeAvatar()
        vm.refreshOwnPhoto()
        advanceUntilIdle()
        assertNull(vm.uiState.value.ownPhotoPath)
    }

    @Test
    fun archived_chats_leave_the_main_list_and_stay_archived_on_new_messages() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.archivedCount)

        client.setChatArchived(DIRECT_CHAT_ID, true)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
        assertEquals(listOf(DIRECT_CHAT_ID), vm.uiState.value.archivedRows.map { it.id })
        assertEquals(1, vm.uiState.value.archivedCount)
        assertTrue(vm.uiState.value.archivedRows.single().archived)
        assertFalse(vm.uiState.value.isEmpty)

        client.simulateIncoming(DIRECT_CHAT_ID, "Still there?", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
        assertEquals("Still there?", vm.uiState.value.archivedRows.single().preview)

        client.setChatArchived(GROUP_CHAT_ID, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(2, vm.uiState.value.archivedCount)
        assertFalse("archived chats are still chats", vm.uiState.value.isEmpty)
    }

    @Test
    fun muted_follows_muted_until_with_forever_and_expiry() = runTest {
        val mutableClock = MutableClock(now, zone)
        val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val client = demo()
        val vm = viewModel(client, clock = mutableClock, ticker = ticker)
        advanceUntilIdle()
        assertFalse(vm.row(GROUP_CHAT_ID).muted)

        client.setChatMuted(GROUP_CHAT_ID, now.toEpochMilli() + 3_600_000)
        client.setChatMuted(DIRECT_CHAT_ID, client.mutedForever())
        advanceUntilIdle()
        assertTrue(vm.row(GROUP_CHAT_ID).muted)
        assertTrue(vm.row(DIRECT_CHAT_ID).muted)

        mutableClock.instant = now.plusSeconds(3_600)
        ticker.emit(Unit)
        advanceUntilIdle()
        assertFalse("expired", vm.row(GROUP_CHAT_ID).muted)
        assertTrue("forever", vm.row(DIRECT_CHAT_ID).muted)

        client.setChatMuted(DIRECT_CHAT_ID, 0)
        advanceUntilIdle()
        assertFalse(vm.row(DIRECT_CHAT_ID).muted)
    }

    @Test
    fun pinned_chats_come_first_and_are_marked() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertFalse(vm.row(DIRECT_CHAT_ID).pinned)

        client.setChatPinned(DIRECT_CHAT_ID, true)
        advanceUntilIdle()
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
        assertTrue(vm.row(DIRECT_CHAT_ID).pinned)
        assertFalse(vm.row(GROUP_CHAT_ID).pinned)

        // A newer message in an unpinned chat does not pass a pinned one.
        client.simulateIncoming(GROUP_CHAT_ID, "Ferry at 7", from = DemoData.nikos.userId)
        advanceUntilIdle()
        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
    }

    @Test
    fun send_state_of_own_last_message_maps_to_ticks_and_others_have_none() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertNull("last message is Nikos'", vm.row(GROUP_CHAT_ID).tick)

        client.sendText(DIRECT_CHAT_ID, "On my way", null)
        advanceUntilIdle()
        assertEquals(TickState.PENDING, vm.row(DIRECT_CHAT_ID).tick)
        for (expected in listOf(TickState.SENT, TickState.DELIVERED, TickState.READ)) {
            client.advanceOutbox()
            advanceUntilIdle()
            assertEquals(expected, vm.row(DIRECT_CHAT_ID).tick)
        }

        client.failNextSend()
        client.sendText(GROUP_CHAT_ID, "Lost", null)
        client.advanceOutbox()
        advanceUntilIdle()
        assertEquals(TickState.FAILED, vm.row(GROUP_CHAT_ID).tick)

        client.simulateIncoming(DIRECT_CHAT_ID, "Great", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertNull(vm.row(DIRECT_CHAT_ID).tick)
    }

    @Test
    fun row_actions_offer_the_undo_of_each_set_state() {
        val row = ChatRowUi("c", "C", null, null, "", ChatTimeLabel.JustNow, 0)
        assertEquals(listOf(ChatListAction.MUTE, ChatListAction.PIN, ChatListAction.ARCHIVE), row.actions)
        assertEquals(
            listOf(ChatListAction.UNMUTE, ChatListAction.UNPIN, ChatListAction.UNARCHIVE),
            row.copy(muted = true, pinned = true, archived = true).actions,
        )
    }

    @Test
    fun actions_change_the_chat_through_the_client() = runTest {
        val fake = demo()
        val muted = mutableListOf<Pair<String, Long>>()
        val client = object : GimClient by fake {
            override suspend fun setChatMuted(chatId: String, mutedUntilMs: Long) {
                muted += chatId to mutedUntilMs
                fake.setChatMuted(chatId, mutedUntilMs)
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        vm.onAction(GROUP_CHAT_ID, ChatListAction.PIN)
        advanceUntilIdle()
        assertTrue(vm.row(GROUP_CHAT_ID).pinned)
        vm.onAction(GROUP_CHAT_ID, ChatListAction.UNPIN)
        advanceUntilIdle()
        assertFalse(vm.row(GROUP_CHAT_ID).pinned)

        vm.mute(GROUP_CHAT_ID, MuteOption.EIGHT_HOURS)
        vm.mute(DIRECT_CHAT_ID, MuteOption.ALWAYS)
        advanceUntilIdle()
        assertEquals(
            listOf(GROUP_CHAT_ID to now.toEpochMilli() + 8 * 3_600_000, DIRECT_CHAT_ID to fake.mutedForever()),
            muted,
        )
        assertTrue(vm.row(GROUP_CHAT_ID).muted)
        vm.onAction(GROUP_CHAT_ID, ChatListAction.UNMUTE)
        advanceUntilIdle()
        assertEquals(GROUP_CHAT_ID to 0L, muted.last())
        assertFalse(vm.row(GROUP_CHAT_ID).muted)

        vm.onAction(GROUP_CHAT_ID, ChatListAction.ARCHIVE)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_CHAT_ID), vm.uiState.value.archivedRows.map { it.id })
        vm.onAction(GROUP_CHAT_ID, ChatListAction.UNARCHIVE)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.archivedCount)
        assertNull(vm.uiState.value.actionError)
    }

    @Test
    fun failed_action_is_reported_until_shown() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()

        client.failNext("setChatArchived", GimException.Storage("disk full"))
        vm.onAction(GROUP_CHAT_ID, ChatListAction.ARCHIVE)
        advanceUntilIdle()
        assertEquals(ChatListAction.ARCHIVE, vm.uiState.value.actionError)
        assertEquals(0, vm.uiState.value.archivedCount)

        vm.errorShown()
        advanceUntilIdle()
        assertNull(vm.uiState.value.actionError)

        client.failNext("setChatMuted", GimException.Storage("disk full"))
        vm.mute(GROUP_CHAT_ID, MuteOption.ONE_HOUR)
        advanceUntilIdle()
        assertEquals(ChatListAction.MUTE, vm.uiState.value.actionError)
    }

    @Test
    fun ticker_reformats_times_without_chat_changes() = runTest {
        // Sunday 2026-07-12 23:32 in Athens; the demo group's last message is two minutes earlier.
        val evening = ZonedDateTime.of(2026, 7, 12, 23, 32, 0, 0, zone).toInstant()
        val mutableClock = MutableClock(evening, zone)
        val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val client = FakeGimClient.withDemoData(backgroundScope, clock = { evening.toEpochMilli() }, zone = zone, outboxDelays = null)
        val vm = viewModel(client, clock = mutableClock, ticker = ticker)
        advanceUntilIdle()
        assertEquals(ChatTimeLabel.Minutes(2), vm.row(GROUP_CHAT_ID).time)

        mutableClock.instant = ZonedDateTime.of(2026, 7, 12, 23, 45, 0, 0, zone).toInstant()
        advanceUntilIdle()
        assertEquals(ChatTimeLabel.Minutes(2), vm.row(GROUP_CHAT_ID).time)
        ticker.emit(Unit)
        advanceUntilIdle()
        assertEquals(ChatTimeLabel.Minutes(15), vm.row(GROUP_CHAT_ID).time)

        mutableClock.instant = ZonedDateTime.of(2026, 7, 13, 0, 0, 1, 0, zone).toInstant()
        ticker.emit(Unit)
        advanceUntilIdle()
        assertEquals(ChatTimeLabel.Yesterday, vm.row(GROUP_CHAT_ID).time)
    }

    @Test
    fun minute_ticker_fires_on_start_at_each_minute_and_at_local_midnight() = runTest {
        // Saturday 2026-10-24 23:58:30 in Athens; DST ends the next night, so that day lasts 25 hours.
        val start = ZonedDateTime.of(2026, 10, 24, 23, 58, 30, 0, zone).toInstant()
        val athens = zone
        val virtualClock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = athens
            override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
        }
        val ticks = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            minuteTicker(virtualClock).collect { ticks += testScheduler.currentTime }
        }
        runCurrent()
        assertEquals(listOf(0L), ticks)

        val toNextMinute = 30_000L
        advanceTimeBy(toNextMinute - 1)
        runCurrent()
        assertEquals(listOf(0L), ticks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, toNextMinute), ticks)

        // The next tick lands exactly on local midnight.
        val midnight = toNextMinute + 60_000L
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(listOf(0L, toNextMinute, midnight), ticks)
        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, zone).toInstant(),
            start.plusMillis(ticks.last()),
        )

        // Through the 25-hour DST day the ticker keeps one tick per minute.
        val dstDay = 25 * 3_600_000L
        advanceTimeBy(dstDay)
        runCurrent()
        assertEquals(3 + 25 * 60, ticks.size)
        assertEquals(
            ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, zone).toInstant(),
            start.plusMillis(ticks.last()),
        )
    }

    @Test
    fun ui_state_is_written_on_main_thread_while_mapping_runs_on_default_dispatcher() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        val default = Executors.newSingleThreadExecutor { Thread(it, "test-default") }.asCoroutineDispatcher()
        val clientScope = CoroutineScope(SupervisorJob() + default)
        val store = ViewModelStore()
        var vmJob: Job? = null
        Dispatchers.setMain(main)
        try {
            runBlocking {
                val client = FakeGimClient.withDemoData(clientScope, { now.toEpochMilli() }, zone, outboxDelays = null)
                val vm = store.own(
                    ChatListViewModel(
                        client = client,
                        clock = clock,
                        avatarDir = tmp.newFolder(),
                        defaultDispatcher = default,
                        locale = Locale.UK,
                        ticker = flowOf(Unit),
                    ),
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
                        if (state.rows.any { it.preview == "ping" }) pingShown.complete(Unit)
                    }
                }
                suspended.set(true)
                client.simulateIncoming(DIRECT_CHAT_ID, "ping", from = DemoData.lukas.userId)
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
}
