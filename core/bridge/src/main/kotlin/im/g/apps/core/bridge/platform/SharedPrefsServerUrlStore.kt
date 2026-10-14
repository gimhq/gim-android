package im.g.apps.core.bridge.platform

import android.content.Context
import im.g.apps.core.bridge.ServerUrlStore

/** [ServerUrlStore] in plain SharedPreferences; the server URL is not secret. */
class SharedPrefsServerUrlStore(context: Context) : ServerUrlStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY_URL, null)

    override fun set(url: String?) {
        val editor = prefs.edit()
        if (url == null) editor.remove(KEY_URL) else editor.putString(KEY_URL, url)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "gim_client"
        const val KEY_URL = "server_url"
    }
}
