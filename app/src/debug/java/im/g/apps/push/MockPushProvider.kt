package im.g.apps.push

import android.content.Context
import androidx.core.content.edit
import im.g.apps.AppContainer
import im.g.apps.BuildConfig
import im.g.apps.core.bridge.PushRegistration
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Debug-only [PushProvider] for gim-push-mock (provider `mock-android`, gim-hub docs/push-mock.md). The token is
 * random per install and kept in the debug preferences. Off until a mock base URL is set: [setBaseUrl] (stored in
 * [AppContainer.DEBUG_PREFS] as [PREF_URL]; PushE2eTest passes its `gimPushMockUrl` argument) or, as the default, the
 * build's `gim.pushMockUrl` Gradle property / `GIM_PUSH_MOCK_URL` env (BuildConfig.PUSH_MOCK_URL).
 *
 * [receive] polls on the caller's coroutine (the app scope, which lives as long as the process) and holds
 * [MockPushService], a foreground service, for that time, so the process keeps polling after the app went to the
 * background and sync stopped.
 */
class MockPushProvider(
    context: Context,
    private val http: MockPushHttp = UrlConnectionMockPushHttp,
    private val keepAlive: KeepAlive = ServiceKeepAlive(context.applicationContext),
) : PushProvider {
    /** Keeps the process polling while [receive] runs. */
    interface KeepAlive {
        fun start()

        fun stop()
    }

    private val prefs = context.applicationContext.getSharedPreferences(AppContainer.DEBUG_PREFS, Context.MODE_PRIVATE)
    private val baseUrl = MutableStateFlow(
        prefs.getString(PREF_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.PUSH_MOCK_URL.takeIf { it.isNotBlank() },
    )

    val token: String
        get() = prefs.getString(PREF_TOKEN, null) ?: newToken().also { prefs.edit { putString(PREF_TOKEN, it) } }

    private val _polling = MutableStateFlow(false)

    /** True while [receive] polls from the mock's newest delivery on: pushes made from then on reach the handler. */
    val polling: StateFlow<Boolean> = _polling.asStateFlow()

    override val registration: Flow<PushRegistration?> =
        baseUrl.map { url -> url?.let { PushRegistration(provider = PROVIDER, token = token) } }

    /** Sets (or clears, with null) the mock base URL, e.g. `http://10.0.2.2:8090`; registration follows. */
    fun setBaseUrl(url: String?) {
        val value = url?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (value == null) remove(PREF_URL) else putString(PREF_URL, value) }
        baseUrl.value = value
    }

    override suspend fun receive(registration: PushRegistration, onPayload: suspend (payload: String) -> Unit) {
        val url = baseUrl.value ?: return
        keepAlive.start()
        try {
            MockPushPoller(http, url, registration.token, onPolling = { _polling.value = true }).run(onPayload)
        } finally {
            _polling.value = false
            keepAlive.stop()
        }
    }

    private class ServiceKeepAlive(private val context: Context) : KeepAlive {
        override fun start() = MockPushService.start(context)

        override fun stop() = MockPushService.stop(context)
    }

    companion object {
        const val PROVIDER = "mock-android"
        const val PREF_URL = "debug_push_mock_url"
        const val PREF_TOKEN = "debug_push_mock_token"

        private fun newToken(): String =
            ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
