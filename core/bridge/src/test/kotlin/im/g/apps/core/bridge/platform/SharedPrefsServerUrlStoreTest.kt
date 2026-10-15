package im.g.apps.core.bridge.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPrefsServerUrlStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun persists_and_clears_the_url() {
        val store = SharedPrefsServerUrlStore(context)
        assertNull(store.get())

        store.set("https://gim.example.eu")
        assertEquals("https://gim.example.eu", SharedPrefsServerUrlStore(context).get())

        store.set(null)
        assertNull(SharedPrefsServerUrlStore(context).get())
    }
}
