package im.g.apps.core.bridge

import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GimClientManagerTest {
    private val hooks = FakePlatformHooks()
    private val store = InMemoryServerUrlStore()

    private fun TestScope.factory() = FakeGimClientFactory { _, _ ->
        FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
    }

    @Test
    fun restore_without_stored_url_returns_null() = runTest {
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)
        assertNull(manager.restore())
        assertNull(manager.client.value)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun connect_validates_creates_stores_and_reuses_the_client() = runTest {
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)

        for (bad in listOf("", "gim.example.eu", "ftp://gim.example.eu", "https://", "https://exa mple.eu")) {
            try {
                manager.connect(bad)
                fail("expected InvalidInput for '$bad'")
            } catch (e: GimException.InvalidInput) {
                assertTrue(e.reason.startsWith("server_url"))
            }
        }
        assertNull(store.get())
        assertTrue(factory.created.isEmpty())

        val client = manager.connect(" ${DemoData.SERVER_URL} ")
        assertEquals(DemoData.SERVER_URL, store.get())
        assertSame(client, manager.client.value)
        assertSame(client, manager.connect(DemoData.SERVER_URL))
        assertEquals(1, factory.created.size)

        val other = manager.connect("http://10.0.2.2:8080")
        assertNotSame(client, other)
        assertEquals("http://10.0.2.2:8080", store.get())
        assertSame(other, manager.client.value)
    }

    @Test
    fun connect_to_another_url_signs_the_replaced_client_out_first() = runTest {
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)
        val first = manager.connect(DemoData.SERVER_URL)
        assertEquals(DemoData.session, first.session())

        val second = manager.connect("http://10.0.2.2:8080")
        assertNull(first.session())
        assertSame(second, manager.client.value)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun connect_drops_a_replaced_client_even_if_its_sign_out_fails() = runTest {
        var created = 0
        val failing = GimClientFactory { config, _ ->
            requireValidServerUrl(config.serverUrl)
            created++
            FakeGimClient(backgroundScope, outboxDelays = null) // no session: signOut throws NotRegistered
        }
        val manager = GimClientManager(failing, hooks, store)
        val first = manager.connect(DemoData.SERVER_URL)
        val second = manager.connect("http://10.0.2.2:8080")
        assertNotSame(first, second)
        assertSame(second, manager.client.value)
        assertEquals(2, created)
    }

    @Test
    fun restore_creates_the_client_from_the_stored_url() = runTest {
        store.set(DemoData.SERVER_URL)
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)

        val client = manager.restore()
        assertSame(client, manager.client.value)
        assertSame(client, manager.restore())
        assertSame(client, manager.connect(DemoData.SERVER_URL))
        assertEquals(1, factory.created.size)
    }

    @Test
    fun sign_out_signs_the_client_out_and_forgets_the_url() = runTest {
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)
        val client = manager.connect(DemoData.SERVER_URL)
        assertEquals(DemoData.session, client.session())

        manager.signOut()
        assertNull(client.session())
        assertNull(store.get())
        assertNull(manager.client.value)
        assertNull(manager.restore())
    }

    @Test
    fun reopen_replaces_the_client_for_the_same_url_without_signing_out() = runTest {
        val factory = factory()
        val manager = GimClientManager(factory, hooks, store)
        assertNull(manager.reopen())
        assertTrue(factory.created.isEmpty())

        val first = manager.connect(DemoData.SERVER_URL)
        val second = manager.reopen()
        assertNotSame(first, second)
        assertSame(second, manager.client.value)
        assertEquals(DemoData.session, first.session())
        assertEquals(DemoData.SERVER_URL, store.get())
        assertEquals(listOf(first, second), factory.created)
    }
}
