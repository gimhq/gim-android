package im.g.apps.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepServerStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(SharedPrefsKeepServerStore.PREFS, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaults_to_the_build_default() {
        assertEquals(KeepServerChoice.Default, SharedPrefsKeepServerStore(context).choice.value)
    }

    @Test
    fun custom_and_off_survive_a_new_store_and_reset_clears_them() {
        val store = SharedPrefsKeepServerStore(context)

        store.set(KeepServerChoice.Custom(CUSTOM))
        assertEquals(KeepServerChoice.Custom(CUSTOM), SharedPrefsKeepServerStore(context).choice.value)

        store.set(KeepServerChoice.Off)
        assertEquals(KeepServerChoice.Off, store.choice.value)
        assertEquals(KeepServerChoice.Off, SharedPrefsKeepServerStore(context).choice.value)

        store.set(KeepServerChoice.Default)
        assertEquals(KeepServerChoice.Default, SharedPrefsKeepServerStore(context).choice.value)
    }

    @Test
    fun resolve_follows_the_choice() {
        assertEquals(BUILT_IN, KeepServerChoice.Default.resolve(BUILT_IN))
        assertNull(KeepServerChoice.Default.resolve(null))
        assertNull(KeepServerChoice.Off.resolve(BUILT_IN))
        assertEquals(CUSTOM, KeepServerChoice.Custom(CUSTOM).resolve(BUILT_IN))
        assertEquals(CUSTOM, KeepServerChoice.Custom(CUSTOM).resolve(null))
    }

    @Test
    fun accepts_https_anywhere_and_http_only_on_the_local_network() {
        listOf(
            "https://keep.example.eu",
            "https://keep.example.eu:8443/base",
            "http://localhost:8081",
            "http://127.0.0.1:8081",
            "http://10.0.2.2:8081",
            "http://192.168.1.20",
            "http://172.20.0.5",
            "http://keep.local",
        ).forEach { assertNull(it, validateKeepServerUrl(it)) }
    }

    @Test
    fun rejects_plain_http_on_the_internet_and_anything_without_a_host() {
        assertEquals(KeepServerUrlError.INSECURE, validateKeepServerUrl("http://keep.example.eu"))
        assertEquals(KeepServerUrlError.INSECURE, validateKeepServerUrl("http://172.32.0.1"))
        assertEquals(KeepServerUrlError.INVALID, validateKeepServerUrl("keep.example.eu"))
        assertEquals(KeepServerUrlError.INVALID, validateKeepServerUrl("ftp://keep.example.eu"))
        assertEquals(KeepServerUrlError.INVALID, validateKeepServerUrl("https://"))
        assertEquals(KeepServerUrlError.INVALID, validateKeepServerUrl("https://exa mple.eu"))
    }

    @Test
    fun normalizes_spaces_and_a_trailing_slash() {
        assertEquals("https://keep.example.eu", normalizeKeepServerUrl("  https://keep.example.eu/ "))
    }

    private companion object {
        const val BUILT_IN = "https://keep.g.im"
        const val CUSTOM = "https://keep.example.eu"
    }
}
