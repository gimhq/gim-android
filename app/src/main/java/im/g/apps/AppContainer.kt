package im.g.apps

import android.content.Context
import android.util.Log
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.ServerUrlStore
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.platform.AndroidMediaPaths
import im.g.apps.core.bridge.platform.AndroidPlatformHooks
import im.g.apps.core.bridge.platform.AndroidReachabilityMonitor
import im.g.apps.core.bridge.platform.SharedPrefsServerUrlStore
import im.g.apps.core.bridge.uniffi.UniffiGimClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Manual DI root: one instance per process, owned by [GimApplication]. Tests must pass [clientFactory] and
 * [serverUrlStore] to run the app against a seeded [FakeGimClient]; the default is the native gim-core client.
 */
class AppContainer(
    context: Context,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    serverUrlStore: ServerUrlStore = SharedPrefsServerUrlStore(context),
    clientFactory: GimClientFactory? = null,
    /** Build-time hub URL prefilled in onboarding; empty when not configured. */
    val defaultHubUrl: String = BuildConfig.DEFAULT_HUB_URL,
) {
    private val appContext = context.applicationContext

    /** Shared by [AndroidPlatformHooks] and [SyncController]; never create a second one. */
    val reachability = AndroidReachabilityMonitor(appContext)
    val hooks = AndroidPlatformHooks(appContext, reachabilityMonitor = reachability)
    val mediaPaths = AndroidMediaPaths(appContext)
    val mediaPathProvider = AppMediaPathProvider(mediaPaths)

    /** The client implementation; never invoked by JVM tests, which pass a fake factory. */
    val clientFactory: GimClientFactory =
        clientFactory ?: defaultClientFactory(useRealGimCore(appContext), serverUrlStore, appScope)

    val manager = GimClientManager(
        factory = this.clientFactory,
        hooks = hooks,
        serverUrlStore = serverUrlStore,
    )

    val sync = SyncController(manager, reachability.reachable, appScope)

    /**
     * Stops sync, signs out of gim-core (which deletes its session, database and secure-store entries) and wipes
     * the platform secret store, keystore key and data dir. Core failures are logged; the local wipe still runs
     * and the client is dropped. Safe to call when signed out, and repeatedly.
     */
    suspend fun signOut() {
        sync.onSignedOut()
        try {
            manager.signOut()
        } catch (e: GimException) {
            Log.w(TAG, "gim-core sign-out failed; wiping local state anyway", e)
        }
        withContext(Dispatchers.IO) { hooks.wipe() }
    }

    companion object {
        private const val TAG = "AppContainer"

        /** Debug-only opt-in (boolean in [DEBUG_PREFS], default false) that replaces gim-core with demo data. */
        const val PREF_DEMO_MODE = "debug_demo_mode"
        const val DEBUG_PREFS = "gim_debug"

        fun isDemoMode(context: Context): Boolean =
            BuildConfig.DEBUG &&
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getBoolean(PREF_DEMO_MODE, false)

        fun useRealGimCore(context: Context): Boolean = BuildConfig.USE_REAL_GIM_CORE && !isDemoMode(context)

        /**
         * The single swap point for the client implementation. gim-core is the default; debug demo mode seeds
         * the fake with demo data so a fresh install goes through onboarding into a populated chat list.
         * [UniffiGimClientFactory] loads the native library only when it creates a client.
         */
        fun defaultClientFactory(
            useRealCore: Boolean,
            serverUrlStore: ServerUrlStore,
            scope: CoroutineScope,
        ): GimClientFactory {
            if (useRealCore) return UniffiGimClientFactory()
            if (!BuildConfig.DEBUG) return FakeGimClientFactory { _, _ -> FakeGimClient(scope) }
            val demo = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope) }
            // A stored URL means the user registered before (restore); otherwise onboarding registers.
            return GimClientFactory { config, hooks ->
                DemoAccountClient(demo.create(config, hooks), registered = serverUrlStore.get() != null)
            }
        }
    }
}
