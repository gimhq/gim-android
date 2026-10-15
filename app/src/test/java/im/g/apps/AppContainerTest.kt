package im.g.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job())
    private val debugPrefs = context.getSharedPreferences(AppContainer.DEBUG_PREFS, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        debugPrefs.edit().clear().commit()
    }

    private fun defaultContainer(storedUrl: String?) =
        AppContainer(context, scope, serverUrlStore = InMemoryServerUrlStore(storedUrl))

    @Test
    fun application_builds_a_container_whose_manager_creates_a_client() = runTest {
        val container = ApplicationProvider.getApplicationContext<GimApplication>().container

        assertNotNull(container.manager.connect(DemoData.SERVER_URL))
        assertNotNull(container.manager.client.value)
    }

    @Test
    fun fresh_install_registers_through_onboarding_into_the_demo_chats() = runTest {
        val container = defaultContainer(storedUrl = null)
        assertNull(container.manager.restore())

        val client = container.manager.connect(DemoData.SERVER_URL)
        assertNull(client.session())
        assertEquals(DemoData.session, client.register("sofia", "Sofia Costa"))

        assertEquals(DemoData.session, client.session())
        val names = client.chatListFlow().first().map { it.name }
        assertTrue(names.containsAll(listOf("Aegean Crew", DemoData.lukas.displayName)))
    }

    @Test
    fun restored_client_has_the_demo_session() = runTest {
        val client = defaultContainer(storedUrl = DemoData.SERVER_URL).manager.restore()

        assertEquals(DemoData.session, client?.session())
    }

    @Test
    fun fake_is_the_default_and_the_debug_pref_selects_the_real_core() {
        assertFalse(BuildConfig.USE_REAL_GIM_CORE)
        assertFalse(AppContainer.useRealGimCore(context))
        assertFalse(AppContainer.defaultClientFactory(false, InMemoryServerUrlStore(), scope) is UniffiGimClientFactory)

        debugPrefs.edit().putBoolean(AppContainer.PREF_USE_REAL_CORE, true).commit()

        assertTrue(AppContainer.useRealGimCore(context))
        assertTrue(AppContainer.defaultClientFactory(true, InMemoryServerUrlStore(), scope) is UniffiGimClientFactory)
    }
}
