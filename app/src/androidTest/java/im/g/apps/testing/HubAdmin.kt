package im.g.apps.testing

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import org.json.JSONObject

/**
 * gim-hub's admin API (hub docs/admin.md) for device tests: the harness enables it on hub A and passes its URL and
 * token as instrumentation arguments `gimAdminUrl` and `gimAdminToken`. [login] takes a session with the token; every
 * mutation sends the session cookie and the CSRF token. Speaks HTTP/1.0 over a plain socket, like
 * [awaitServerReachable], because the app's network security config forbids cleartext through HttpURLConnection.
 */
class HubAdmin private constructor(private val uri: URI, private val cookie: String, private val csrf: String) {
    fun suspendUser(userId: String) {
        expect(204, "POST", "/admin/api/users/$userId/suspend", "{}")
    }

    /** Lifts the suspension of [userId]; true when they were suspended. */
    fun unsuspendUser(userId: String): Boolean =
        expect(setOf(204, 409), "POST", "/admin/api/users/$userId/unsuspend", "{}") == 204

    fun addPolicyEntry(userId: String) {
        expect(200, "POST", "/admin/api/users/policy/entries", JSONObject().put("user_id", userId).toString())
    }

    /** Removes [userId] from the user policy list; true when it was listed. */
    fun removePolicyEntry(userId: String): Boolean =
        expect(setOf(200, 404), "DELETE", "/admin/api/users/policy/entries/$userId", null) == 200

    /** Sets `GIM_HUB_USER_POLICY` (`open`, `allow` or `deny`) as a stored setting. */
    fun setUserPolicy(mode: String) {
        expect(200, "PUT", "/admin/api/settings/GIM_HUB_USER_POLICY", JSONObject().put("value", mode).toString())
    }

    /** Deletes the stored `GIM_HUB_USER_POLICY`: the default, `open`, applies. */
    fun resetUserPolicy() {
        expect(200, "DELETE", "/admin/api/settings/GIM_HUB_USER_POLICY", null)
    }

    private fun expect(status: Int, method: String, path: String, body: String?) =
        expect(setOf(status), method, path, body)

    private fun expect(statuses: Set<Int>, method: String, path: String, body: String?): Int {
        val response = request(uri, method, path, body, mapOf("Cookie" to cookie, "X-CSRF-Token" to csrf))
        Log.i(TAG, "$method $path -> ${response.status}")
        if (response.status !in statuses) {
            throw AssertionError("admin API $method $path answered ${response.status}: ${response.body}")
        }
        return response.status
    }

    private class Response(val status: Int, val headers: List<Pair<String, String>>, val body: String)

    companion object {
        private const val TAG = "HubAdmin"
        private const val IO_TIMEOUT_MS = 10_000

        /** Logs in at [adminUrl] (`http://host:port`) with [token]. */
        fun login(adminUrl: String, token: String): HubAdmin {
            val uri = URI(adminUrl.trimEnd('/'))
            require(uri.scheme == "http") { "only http:// admin URLs are supported, got $adminUrl" }
            val response = request(uri, "POST", "/admin/api/login", JSONObject().put("token", token).toString(), emptyMap())
            if (response.status != 200) throw AssertionError("admin login answered ${response.status}: ${response.body}")
            val cookie = response.headers.firstOrNull { (name, value) ->
                name.equals("Set-Cookie", ignoreCase = true) && value.startsWith("gim_admin_session=")
            }?.second?.substringBefore(';') ?: throw AssertionError("admin login set no session cookie")
            return HubAdmin(uri, cookie, JSONObject(response.body).getString("csrf_token"))
        }

        private fun request(uri: URI, method: String, path: String, body: String?, headers: Map<String, String>): Response {
            val port = if (uri.port > 0) uri.port else 80
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.0\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            val raw = Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, port), IO_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
                socket.getInputStream().readBytes().decodeToString()
            }
            val headerEnd = raw.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: throw IOException("incomplete response: $raw")
            val lines = raw.substring(0, headerEnd).split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("bad status line: ${lines.first()}")
            val responseHeaders = lines.drop(1).map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            return Response(status, responseHeaders, raw.substring(headerEnd + 4))
        }
    }
}
