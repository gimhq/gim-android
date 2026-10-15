package im.g.apps.testing

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Blocks until gim-server answers `GET /healthz` with 200 from this device, polling once a second for at most
 * [timeoutMs]. An emulator reports `sys.boot_completed` before it has a default network, and requests made in
 * that window hang, so device tests call this before the app talks to the server. Throws [AssertionError]
 * naming [serverUrl] and the last failure when the server never answers.
 *
 * Speaks HTTP/1.0 over a plain socket: the app's network security config forbids cleartext through
 * HttpURLConnection, and the test must not need a debug-only config to probe the server.
 */
fun awaitServerReachable(context: Context, serverUrl: String, timeoutMs: Long = 90_000) {
    val uri = URI(serverUrl.trimEnd('/') + "/healthz")
    val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
    require(uri.scheme == "http") { "only http:// server URLs can be probed, got $serverUrl" }
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var lastFailure = "no attempt"
    while (SystemClock.elapsedRealtime() < deadline) {
        lastFailure = if (connectivity.activeNetwork == null) {
            "no default network"
        } else {
            try {
                val status = healthStatus(uri.host, port, uri.rawPath)
                if (status == 200) return
                "HTTP $status"
            } catch (e: IOException) {
                e.toString()
            }
        }
        Log.i(TAG, "waiting for $uri: $lastFailure")
        SystemClock.sleep(POLL_MS)
    }
    throw AssertionError("gim-server not reachable at $uri within $timeoutMs ms (last: $lastFailure)")
}

private fun healthStatus(host: String, port: Int, path: String): Int =
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), IO_TIMEOUT_MS)
        socket.soTimeout = IO_TIMEOUT_MS
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val statusLine = socket.getInputStream().bufferedReader().readLine() ?: throw IOException("empty response")
        statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
    }

private const val TAG = "ServerReachability"
private const val POLL_MS = 1_000L
private const val IO_TIMEOUT_MS = 5_000
