package im.g.apps.core.bridge.flow

import app.cash.turbine.test
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GimClientFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.demo() = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        cacheDir = tmp.newFolder(),
        outboxDelays = null,
    )

    @Test
    fun chat_list_flow_emits_initial_state_updates_and_cancels() = runTest {
        val client = demo()
        client.chatListFlow().test {
            assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), awaitItem().map { it.id })
            assertEquals(1, client.activeSubscriptions)

            testScheduler.advanceTimeBy(1_000)
            client.simulateIncoming(DIRECT_CHAT_ID, "Moored in Split", from = DemoData.lukas.id)
            val updated = awaitItem()
            assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), updated.map { it.id })
            assertEquals(1, updated[0].unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun timeline_flow_emits_page_then_new_items() = runTest {
        val client = demo()
        client.timelineFlow(DIRECT_CHAT_ID).test {
            val initial = awaitItem()
            assertEquals(20, initial.size)

            val id = client.sendText(DIRECT_CHAT_ID, "Lines are coiled", replyTo = null)
            val updated = awaitItem()
            assertEquals(21, updated.size)
            assertEquals(id, (updated[0] as TimelineItem.Message).id)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun connection_flow_emits_current_state_then_changes() = runTest {
        val client = demo()
        client.connectionFlow().test {
            assertEquals(ConnectionState.OFFLINE, awaitItem())
            client.setConnection(ConnectionState.ONLINE)
            assertEquals(ConnectionState.ONLINE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }

    @Test
    fun typing_flow_starts_empty_and_filters_by_chat() = runTest {
        val client = demo()
        client.typingFlow(GROUP_CHAT_ID).test {
            assertTrue(awaitItem().isEmpty())
            client.simulateTyping(DIRECT_CHAT_ID, DemoData.lukas.id, typing = true)
            client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.id, typing = true)
            assertEquals(listOf(DemoData.ana), awaitItem())
            client.simulateTyping(GROUP_CHAT_ID, DemoData.ana.id, typing = false)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(0, client.activeSubscriptions)
    }
}
