package im.g.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.LocalTimeZone
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
import java.io.File
import java.time.Instant
import java.util.TimeZone

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
    fun clients_follow_the_device_time_zone_from_creation_and_on_changes() = runTest {
        val zones = mutableListOf<LocalTimeZone?>()
        val container = AppContainer(
            context,
            scope,
            serverUrlStore = InMemoryServerUrlStore(),
            clientFactory = GimClientFactory { _, _ ->
                val fake = FakeGimClient(scope)
                object : GimClient by fake {
                    override fun setLocalTimeZone(zone: LocalTimeZone?) {
                        zones += zone
                        fake.setLocalTimeZone(zone)
                    }
                }
            },
        )

        container.onTimeZoneChanged()
        assertEquals(emptyList<LocalTimeZone?>(), zones)
        container.manager.connect(DemoData.SERVER_URL)
        assertEquals(listOf<LocalTimeZone?>(SystemLocalTimeZone), zones)
        container.onTimeZoneChanged()
        assertEquals(listOf<LocalTimeZone?>(SystemLocalTimeZone, SystemLocalTimeZone), zones)
    }

    @Test
    fun system_local_time_zone_reports_the_default_zone_offset_with_dst() {
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
            assertEquals(3 * 3600, SystemLocalTimeZone.utcOffsetSecs(Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()))
            assertEquals(2 * 3600, SystemLocalTimeZone.utcOffsetSecs(Instant.parse("2026-12-01T12:00:00Z").toEpochMilli()))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
            assertEquals(0, SystemLocalTimeZone.utcOffsetSecs(Instant.parse("2026-12-01T12:00:00Z").toEpochMilli()))
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun fresh_install_registers_through_onboarding_into_the_demo_chats() = runTest {
        val container = demoContainer(storedUrl = null)
        assertNull(container.manager.restore())

        val client = container.manager.connect(DemoData.SERVER_URL)
        assertNull(client.session())
        assertNull(client.userId())
        assertEquals(FakeGimClient.fakeRecoveryCode(1), client.createIdentity("Sofia Costa"))

        assertEquals(DemoData.session, client.session())
        assertEquals(DemoData.session.userId, client.userId())
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

    @Test
    fun sign_out_wipes_local_state_is_idempotent_and_allows_registering_again() = runTest {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val factory = AppContainer.defaultClientFactory(useRealCore = false, serverUrlStore = store, scope = scope)
        val container = AppContainer(context, scope, serverUrlStore = store, clientFactory = factory)
        val restored = container.manager.restore()!!
        val (secrets, data) = seedLocalState(container)

        container.signOut()

        assertNull(restored.session())
        assertNull(store.get())
        assertNull(container.manager.client.value)
        assertFalse(secrets.exists())
        assertFalse(data.exists())

        container.signOut()

        val client = container.manager.connect(DemoData.SERVER_URL)
        client.createIdentity("Sofia Costa")
        assertEquals(DemoData.session, client.session())
    }

    @Test
    fun sign_out_still_wipes_and_drops_the_client_when_core_fails() = runTest {
        val store = InMemoryServerUrlStore(DemoData.SERVER_URL)
        val failing = object : GimClient by FakeGimClient(scope) {
            override suspend fun signOut() = throw GimException.Network("offline")
        }
        val container = AppContainer(context, scope, serverUrlStore = store, clientFactory = GimClientFactory { _, _ -> failing })
        container.manager.restore()
        val (secrets, data) = seedLocalState(container)

        container.signOut()

        assertNull(store.get())
        assertNull(container.manager.client.value)
        assertFalse(secrets.exists())
        assertFalse(data.exists())
    }

    private fun seedLocalState(container: AppContainer): Pair<File, File> {
        val secrets = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }
        File(secrets, "leftover").writeText("sealed")
        val data = File(container.hooks.dataDir())
        File(data, "gim.db").writeText("db")
        return secrets to data
    }
}
