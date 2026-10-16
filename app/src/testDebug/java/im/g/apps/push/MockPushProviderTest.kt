package im.g.apps.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.AppContainer
import im.g.apps.core.bridge.PushRegistration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockPushProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingKeepAlive : MockPushProvider.KeepAlive {
        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(AppContainer.DEBUG_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun registration_is_off_until_a_mock_url_is_set_and_keeps_its_random_token() = runTest {
        val provider = MockPushProvider(context, http = { awaitCancellation() }, keepAlive = RecordingKeepAlive())
        assertNull(provider.registration.first())

        provider.setBaseUrl(" http://10.0.2.2:8090 ")
        val registration = checkNotNull(provider.registration.first())
        assertEquals("mock-android", registration.provider)
        assertTrue(registration.token, Regex("[0-9a-f]{32}").matches(registration.token))

        // Another instance (next launch) keeps URL and token.
        val again = MockPushProvider(context, http = { awaitCancellation() }, keepAlive = RecordingKeepAlive())
        assertEquals(registration, again.registration.first())

        again.setBaseUrl(null)
        assertNull(again.registration.first())
    }

    @Test
    fun receive_polls_its_token_and_holds_the_keep_alive_while_running() = runTest {
        val urls = mutableListOf<String>()
        val keepAlive = RecordingKeepAlive()
        val provider = MockPushProvider(context, http = { urls += it; awaitCancellation() }, keepAlive = keepAlive)
        provider.setBaseUrl("http://192.168.68.220:18190")

        val job = launch { provider.receive(PushRegistration("mock-android", "tok-1")) {} }
        advanceUntilIdle()
        assertEquals(listOf("start"), keepAlive.calls)
        assertEquals(listOf("http://192.168.68.220:18190/deliveries?token=tok-1&since=0"), urls)

        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf("start", "stop"), keepAlive.calls)
    }
}
