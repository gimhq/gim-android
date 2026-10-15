package im.g.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.platform.AndroidPlatformHooks
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
import org.junit.Assert.assertSame
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

    /** A container on the demo-mode factory, injected explicitly: the default would load gim-core. */
    private fun demoContainer(storedUrl: String?): AppContainer {
        val store = InMemoryServerUrlStore(storedUrl)
        val factory = AppContainer.defaultClientFactory(useRealCore = false, serverUrlStore = store, scope = scope)
        return AppContainer(context, scope, serverUrlStore = store, clientFactory = factory)
    }

    @Test
    fun application_container_defaults_to_gim_core_without_creating_a_client() {
        val container = ApplicationProvider.getApplicationContext<GimApplication>().container

        assertTrue(container.clientFactory is UniffiGimClientFactory)
        assertTrue(container.hooks is AndroidPlatformHooks)
        assertNull(container.manager.client.value)
    }

    @Test
    fun manager_passes_the_android_platform_hooks_to_the_factory() = runTest {
        var received: PlatformHooks? = null
        val container = AppContainer(
            context,
            scope,
            serverUrlStore = InMemoryServerUrlStore(),
            clientFactory = GimClientFactory { _, hooks -> FakeGimClient(scope).also { received = hooks } },
        )

        assertNotNull(container.manager.connect(DemoData.SERVER_URL))
        assertSame(container.hooks, received)
    }

    @Test
    fun fresh_install_registers_through_onboarding_into_the_demo_chats() = runTest {
        val container = demoContainer(storedUrl = null)
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
        val client = demoContainer(storedUrl = DemoData.SERVER_URL).manager.restore()

        assertEquals(DemoData.session, client?.session())
    }

    @Test
    fun gim_core_is_the_default_and_the_debug_pref_opts_into_demo_mode() {
        assertTrue(BuildConfig.USE_REAL_GIM_CORE)
        assertFalse(AppContainer.isDemoMode(context))
        assertTrue(AppContainer.useRealGimCore(context))
        // Selected, not invoked: the factory loads the native library only in create().
        assertTrue(AppContainer(context, scope, InMemoryServerUrlStore()).clientFactory is UniffiGimClientFactory)

        debugPrefs.edit().putBoolean(AppContainer.PREF_DEMO_MODE, true).commit()

        assertTrue(AppContainer.isDemoMode(context))
        assertFalse(AppContainer.useRealGimCore(context))
        assertFalse(AppContainer(context, scope, InMemoryServerUrlStore()).clientFactory is UniffiGimClientFactory)
    }

    @Test
    fun demo_mode_container_restores_the_demo_session() = runTest {
        debugPrefs.edit().putBoolean(AppContainer.PREF_DEMO_MODE, true).commit()

        val client = AppContainer(context, scope, InMemoryServerUrlStore(DemoData.SERVER_URL)).manager.restore()

        assertEquals(DemoData.session, client?.session())
    }
}
