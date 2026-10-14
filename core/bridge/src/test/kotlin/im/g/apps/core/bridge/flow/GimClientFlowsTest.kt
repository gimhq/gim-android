package im.g.apps.core.bridge.flow

import app.cash.turbine.test
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class GimClientFlowsTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val base = 1_783_839_600_000L // 2026-07-12T10:00+03:00

    private fun TestScope.demo() = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { base + testScheduler.currentTime },
        zone = zone,
        outboxDelays = null,
    )

    @Test
    fun chat_list_flow_emits_initial_state_updates_and_closes_the_handle() = runTest {
        val client = demo()
        client.chatListFlow().test {
            assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), awaitItem().map { it.chatId })
            assertEquals(1, client.activeSubscriptions)

            testScheduler.advanceTimeBy(1_000)
            client.simulateIncoming(DIRECT_CHAT_ID, "Moored in Split", from = DemoData.lukas.userId)
            val updated = awaitItem()
            assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), updated.map { it.chatId })
            assertEquals(1, updated[0].unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun timeline_items_flow_emits_loaded_items_then_local_echo_at_the_end() = runTest {
        val client = demo()
        client.timeline(DIRECT_CHAT_ID).itemsFlow().test {
            val initial = awaitItem()
            assertTrue(initial.first() is TimelineItem.DateSeparator)

            val id = client.sendText(DIRECT_CHAT_ID, "Lines are coiled", replyTo = null)
            val updated = awaitItem()
            assertEquals(initial.size + 1, updated.size)
            assertEquals(id, (updated.last() as TimelineItem.Message).item.messageId)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun connection_state_flow_emits_current_state_then_changes() = runTest {
        val client = demo()
        client.connectionStateFlow().test {
            assertEquals(ConnectionState.OFFLINE, awaitItem())
            client.setConnection(ConnectionState.CONNECTED)
            assertEquals(ConnectionState.CONNECTED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun typing_flow_emits_current_set_then_changes_of_its_chat() = runTest {
        val client = demo()
        client.typingFlow(GROUP_CHAT_ID).test {
            assertTrue(awaitItem().isEmpty())
            client.simulateTyping(DIRECT_CHAT_ID, DemoData.lukas.userId, typing = true)
            client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.userId, typing = true)
            assertEquals(listOf(DemoData.ana.userId), awaitItem())
            client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.userId, typing = false)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun timeline_flow_keeps_every_diff_in_order_under_foreign_thread_callbacks() = runBlocking {
        val client = FakeGimClient.withDemoData(scope = this, clock = { base }, zone = zone, outboxDelays = null)
        val timeline = client.timeline(DIRECT_CHAT_ID)
        val initialSize = timeline.itemsFlow().first().size
        val threads = 4
        val perThread = 50

        val result = async(Dispatchers.Default) {
            timeline.itemsFlow().first { it.size == initialSize + threads * perThread }
        }
        withTimeout(10_000) { while (client.activeSubscriptions == 0) delay(1) }

        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { i ->
                    client.simulateIncoming(DIRECT_CHAT_ID, "t$t-$i", from = DemoData.lukas.userId)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val collected = withTimeout(10_000) { result.await() }
        assertEquals(timeline.itemsFlow().first(), collected)
        for (t in 0 until threads) {
            val bodies = collected.mapNotNull { (it as? TimelineItem.Message)?.item?.content?.toString() }
                .filter { it.contains("body=t$t-") }
            assertEquals((0 until perThread).map { "Text(body=t$t-$it)" }, bodies)
        }
        withTimeout(10_000) { while (client.activeSubscriptions != 0) delay(1) }
    }
}
