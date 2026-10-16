package im.g.apps.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** org.json needs the Robolectric runtime. */
@RunWith(AndroidJUnit4::class)
class MockPushPollerTest {
    /** Answers with [answers] in order (an [IOException] is thrown), then waits forever like an idle long-poll. */
    private class FakeHttp(vararg answers: Any) : MockPushHttp {
        private val queue = ArrayDeque(answers.toList())
        val urls = mutableListOf<String>()

        override suspend fun get(url: String): String {
            urls += url
            val answer = queue.removeFirstOrNull() ?: awaitCancellation()
            if (answer is IOException) throw answer
            return answer as String
        }
    }

    private fun delivery(seq: Long, gid: String) =
        """{"seq":$seq,"received_at":1,"delivery_id":"d$seq","body":{"v":1,"registration":{"provider":"mock-android",""" +
            """"token":"tok"},"collapse_id":"c$seq","payload":{"v":1,"type":"message","gid":"$gid","seq":$seq}}}"""

    private fun page(next: Long, vararg deliveries: String) = """{"deliveries":[${deliveries.joinToString(",")}],"next":$next}"""

    @Test
    fun skips_earlier_deliveries_then_long_polls_and_hands_payloads_over_in_order() = runTest {
        val http = FakeHttp(
            page(5, delivery(5, "old")),
            page(7, delivery(6, "g-a"), delivery(7, "g-b")),
            page(7),
        )
        val payloads = mutableListOf<String>()
        val job = launch { MockPushPoller(http, "http://10.0.2.2:8090/", "tok").run { payloads += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("g-a", "g-b"), payloads.map { JSONObject(it).getString("gid") })
        assertEquals(
            listOf(
                "http://10.0.2.2:8090/deliveries?token=tok&since=0",
                "http://10.0.2.2:8090/deliveries?token=tok&since=5&wait=30",
                "http://10.0.2.2:8090/deliveries?token=tok&since=7&wait=30",
                "http://10.0.2.2:8090/deliveries?token=tok&since=7&wait=30",
            ),
            http.urls,
        )
    }

    @Test
    fun retries_failures_and_keeps_its_position() = runTest {
        val http = FakeHttp(
            IOException("connection refused"),
            page(3),
            "<html>",
            IOException("HTTP 502"),
            page(4, delivery(4, "g-a")),
        )
        val payloads = mutableListOf<String>()
        val job = launch { MockPushPoller(http, "http://mock:8090", "tok", retryDelayMs = 1_000).run { payloads += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("g-a"), payloads.map { JSONObject(it).getString("gid") })
        assertEquals(
            listOf("since=0", "since=0", "since=3&wait=30", "since=3&wait=30", "since=3&wait=30", "since=4&wait=30"),
            http.urls.map { it.substringAfter("token=tok&") },
        )
    }
}
