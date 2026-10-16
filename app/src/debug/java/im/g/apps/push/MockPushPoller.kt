package im.g.apps.push

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** GET on the mock push server; throws [IOException] for transport failures and non-2xx answers. */
fun interface MockPushHttp {
    suspend fun get(url: String): String
}

/** [MockPushHttp] over [HttpURLConnection]; the read timeout covers the server's 30 s long-poll. */
object UrlConnectionMockPushHttp : MockPushHttp {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000
}

/**
 * Long-polls gim-push-mock (gim-hub docs/push-mock.md) for the deliveries of [token]: a first call without `wait` only
 * reads `next` (deliveries made before polling started are skipped), then `GET /deliveries?token=&since=<next>&wait=30`
 * in a loop, handing each delivery's `body.payload` to the push handler in order. [onPolling] runs once that first
 * call succeeded. Failures are retried after [retryDelayMs]; [run] returns only by cancellation.
 */
class MockPushPoller(
    private val http: MockPushHttp,
    baseUrl: String,
    token: String,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val onPolling: () -> Unit = {},
) {
    private val deliveriesUrl = baseUrl.trimEnd('/') + "/deliveries?token=" + URLEncoder.encode(token, "UTF-8")

    suspend fun run(onPayload: suspend (payload: String) -> Unit): Nothing {
        var next: Long? = null
        while (true) {
            val since = next
            val url = if (since == null) "$deliveriesUrl&since=0" else "$deliveriesUrl&since=$since&wait=$WAIT_SECONDS"
            val page = try {
                parse(http.get(url))
            } catch (e: IOException) {
                Log.w(TAG, "Polling the push mock failed: $e")
                delay(retryDelayMs)
                continue
            } catch (e: JSONException) {
                Log.w(TAG, "Unexpected push mock answer: $e")
                delay(retryDelayMs)
                continue
            }
            next = maxOf(page.next, since ?: 0)
            if (since == null) {
                Log.i(TAG, "Polling the push mock from seq ${page.next}")
                onPolling()
                continue
            }
            page.payloads.forEach {
                Log.i(TAG, "Push delivered")
                onPayload(it)
            }
        }
    }

    private class Page(val next: Long, val payloads: List<String>)

    private fun parse(body: String): Page {
        val json = JSONObject(body)
        val deliveries = json.getJSONArray("deliveries")
        val payloads = (0 until deliveries.length()).map {
            deliveries.getJSONObject(it).getJSONObject("body").getJSONObject("payload").toString()
        }
        return Page(json.getLong("next"), payloads)
    }

    private companion object {
        const val TAG = "MockPushPoller"
        const val WAIT_SECONDS = 30
        const val RETRY_DELAY_MS = 2_000L
    }
}
