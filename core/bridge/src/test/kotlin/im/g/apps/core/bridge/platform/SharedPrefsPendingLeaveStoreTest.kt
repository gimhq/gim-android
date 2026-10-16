package im.g.apps.core.bridge.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPrefsPendingLeaveStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pending_leaves_survive_a_restart_until_removed_or_cleared() {
        val store = SharedPrefsPendingLeaveStore(context)
        assertEquals(emptyMap<String, Long>(), store.pending.value)

        store.add("chat-1", 1_000)
        store.add("chat-2", 2_000)
        assertEquals(mapOf("chat-1" to 1_000L, "chat-2" to 2_000L), SharedPrefsPendingLeaveStore(context).pending.value)

        store.remove("chat-1")
        assertEquals(mapOf("chat-2" to 2_000L), store.pending.value)
        assertEquals(mapOf("chat-2" to 2_000L), SharedPrefsPendingLeaveStore(context).pending.value)

        store.clear()
        assertEquals(emptyMap<String, Long>(), SharedPrefsPendingLeaveStore(context).pending.value)
    }
}
