package im.g.apps.core.bridge.platform

import android.content.Context
import im.g.apps.core.bridge.PendingLeaveStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** [PendingLeaveStore] in plain SharedPreferences (chat id -> request time); chat ids are not secret. */
class SharedPrefsPendingLeaveStore(context: Context) : PendingLeaveStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap())

    override val pending: StateFlow<Map<String, Long>> = state.asStateFlow()

    override fun add(chatId: String, requestedAtMs: Long) {
        prefs.edit().putLong(chatId, requestedAtMs).apply()
        state.update { it + (chatId to requestedAtMs) }
    }

    override fun remove(chatId: String) {
        prefs.edit().remove(chatId).apply()
        state.update { it - chatId }
    }

    override fun clear() {
        prefs.edit().clear().apply()
        state.value = emptyMap()
    }

    private companion object {
        const val PREFS_NAME = "gim_pending_leaves"
    }
}
