package im.g.apps.core.bridge.uniffi

import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.FakePlatformHooks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Listener and hook adapters are plain Kotlin objects; nothing here loads the native library.
class AdaptersTest {
    @Test
    fun chatListListenerForwardsMappedDiffsInOrder() {
        val received = mutableListOf<List<ChatListDiff>>()
        val adapter = ChatListListenerAdapter { received += it }

        adapter.onUpdate(listOf(im.g.gimcore.ChatListDiff.Clear, im.g.gimcore.ChatListDiff.Remove(1u)))
        adapter.onUpdate(listOf(im.g.gimcore.ChatListDiff.PopBack))

        assertEquals(
            listOf(listOf(ChatListDiff.Clear, ChatListDiff.Remove(1)), listOf(ChatListDiff.PopBack)),
            received,
        )
    }

    @Test
    fun timelineListenerForwardsMappedDiffs() {
        val received = mutableListOf<List<TimelineDiff>>()
        TimelineListenerAdapter { received += it }
            .onUpdate(listOf(im.g.gimcore.TimelineDiff.PushBack(im.g.gimcore.TimelineItem.DateSeparator(1UL))))

        assertEquals(listOf(listOf(TimelineDiff.PushBack(TimelineItem.DateSeparator(1L)))), received)
    }

    @Test
    fun connectionStateAndTypingListenersForward() {
        val states = mutableListOf<ConnectionState>()
        ConnectionStateListenerAdapter { states += it }.onChange(im.g.gimcore.ConnectionState.SYNCING)
        assertEquals(listOf(ConnectionState.SYNCING), states)

        val typing = mutableListOf<List<String>>()
        TypingListenerAdapter { typing += it }.onUpdate(listOf("u1", "u2"))
        assertEquals(listOf(listOf("u1", "u2")), typing)
    }

    @Test
    fun platformHooksDelegate() {
        val hooks = FakePlatformHooks()
        val adapter = PlatformHooksAdapter(hooks)

        adapter.secureStoreSet("k", byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), adapter.secureStoreGet("k"))
        adapter.secureStoreDelete("k")
        assertNull(adapter.secureStoreGet("k"))
        assertEquals(hooks.dataDir(), adapter.dataDir())
        assertEquals(hooks.isNetworkReachable(), adapter.isNetworkReachable())
    }

    @Test
    fun secureStoreSetMapsMirrorErrors() {
        val failure = GimException.Crypto("keystore locked")
        val error = runCatching { PlatformHooksAdapter(failingHooks(failure)).secureStoreSet("k", byteArrayOf()) }
            .exceptionOrNull()

        assertTrue(error is im.g.gimcore.GimException.Crypto)
        assertEquals("keystore locked", (error as im.g.gimcore.GimException.Crypto).reason)
        assertSame(failure, error.cause)
    }

    @Test
    fun secureStoreSetMapsOtherErrorsToStorage() {
        val failure = IllegalStateException("disk full")
        val error = runCatching { PlatformHooksAdapter(failingHooks(failure)).secureStoreSet("k", byteArrayOf()) }
            .exceptionOrNull()

        assertTrue(error is im.g.gimcore.GimException.Storage)
        assertEquals("disk full", (error as im.g.gimcore.GimException.Storage).reason)
        assertSame(failure, error.cause)
    }

    @Test
    fun secureStoreGetMapsMirrorAndOtherErrors() {
        val locked = GimException.Storage("keystore locked")
        val mapped = runCatching { PlatformHooksAdapter(failingHooks(locked)).secureStoreGet("k") }.exceptionOrNull()
        assertTrue(mapped is im.g.gimcore.GimException.Storage)
        assertEquals("keystore locked", (mapped as im.g.gimcore.GimException.Storage).reason)
        assertSame(locked, mapped.cause)

        val other = IllegalStateException("io")
        val wrapped = runCatching { PlatformHooksAdapter(failingHooks(other)).secureStoreGet("k") }.exceptionOrNull()
        assertTrue(wrapped is im.g.gimcore.GimException.Storage)
        assertSame(other, wrapped?.cause)
    }

    private fun failingHooks(failure: Exception) = object : im.g.apps.core.bridge.PlatformHooks by FakePlatformHooks() {
        override fun secureStoreGet(key: String): ByteArray? = throw failure

        override fun secureStoreSet(key: String, value: ByteArray) = throw failure
    }
}
