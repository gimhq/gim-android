package im.g.apps.core.bridge.uniffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the ART abort "attempting to detach while still running code".
 *
 * On an unconfined dispatcher gim-core's future continuation resumes the coroutine on the tokio thread that
 * woke it, so the next poll (and the PlatformHooks callbacks it makes) runs nested inside the continuation
 * callback. JNA used to detach the thread when the inner callback returned, with the outer callback's frames
 * still on the stack. Reopening a client over stored identity reads the secure store during that poll; the
 * test passes when the process survives every reopen.
 */
@RunWith(AndroidJUnit4::class)
class CallbackThreadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serverUrl = InstrumentationRegistry.getArguments().getString("gimServerUrl") ?: "http://10.0.2.2:8080"
    private val dir = File(context.cacheDir, "callback-thread-${UUID.randomUUID()}")
    private val hooks = MemoryHooks(dir)
    private val factory = UniffiGimClientFactory()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun reopeningAClientOverStoredIdentitySurvivesNestedCallbacks() = runBlocking(Dispatchers.Unconfined) {
        val first = factory.create(ClientConfig(serverUrl), hooks)
        createIdentityWhenReachable(first)
        val userId = checkNotNull(first.userId())

        repeat(REOPENS) {
            val client = factory.create(ClientConfig(serverUrl), hooks)
            assertEquals(userId, client.userId())
            client.connectionState { }.close()
        }
        // Several reopens at once spread the nested callbacks over more tokio threads.
        (1..PARALLEL).map {
            async(Dispatchers.IO) {
                withContext(Dispatchers.Unconfined) { factory.create(ClientConfig(serverUrl), hooks).userId() }
            }
        }.awaitAll().forEach { assertEquals(userId, it) }
    }

    /** The emulator may report boot before its network is up; retry network errors for a while. */
    private suspend fun createIdentityWhenReachable(client: GimClient) {
        val deadline = System.currentTimeMillis() + NETWORK_TIMEOUT_MS
        while (true) {
            try {
                client.createIdentity("Callback Thread")
                return
            } catch (e: GimException.Network) {
                if (System.currentTimeMillis() > deadline) throw e
                delay(1_000)
            }
        }
    }

    private class MemoryHooks(private val dir: File) : PlatformHooks {
        private val secrets = ConcurrentHashMap<String, ByteArray>()

        override fun secureStoreGet(key: String): ByteArray? = secrets[key]

        override fun secureStoreSet(key: String, value: ByteArray) {
            secrets[key] = value
        }

        override fun secureStoreDelete(key: String) {
            secrets.remove(key)
        }

        override fun dataDir(): String = dir.apply { mkdirs() }.absolutePath

        override fun isNetworkReachable(): Boolean = true
    }

    private companion object {
        const val REOPENS = 30
        const val PARALLEL = 8
        const val NETWORK_TIMEOUT_MS = 90_000L
    }
}
