package im.g.apps

import android.content.Context
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
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

/**
 * Manual DI root: one instance per process, owned by [GimApplication]. Tests pass [clientFactory] and
 * [serverUrlStore] to run the app against a seeded [FakeGimClient].
 */
class AppContainer(
    context: Context,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    serverUrlStore: ServerUrlStore = SharedPrefsServerUrlStore(context),
    clientFactory: GimClientFactory? = null,
) {
    private val appContext = context.applicationContext

    /** Shared by [AndroidPlatformHooks] and [SyncController]; never create a second one. */
    val reachability = AndroidReachabilityMonitor(appContext)
    val hooks = AndroidPlatformHooks(appContext, reachabilityMonitor = reachability)
    val mediaPaths = AndroidMediaPaths(appContext)
    val mediaPathProvider = AppMediaPathProvider(mediaPaths)

    val manager = GimClientManager(
        factory = clientFactory ?: defaultClientFactory(useRealGimCore(appContext), serverUrlStore, appScope),
        hooks = hooks,
        serverUrlStore = serverUrlStore,
    )

    val sync = SyncController(manager, reachability.reachable, appScope)

    companion object {
        /** Debug-only override of [BuildConfig.USE_REAL_GIM_CORE] (boolean in [DEBUG_PREFS]). */
        const val PREF_USE_REAL_CORE = "debug_use_real_core"
        const val DEBUG_PREFS = "gim_debug"

        fun useRealGimCore(context: Context): Boolean =
            BuildConfig.USE_REAL_GIM_CORE ||
                BuildConfig.DEBUG &&
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getBoolean(PREF_USE_REAL_CORE, false)

        /**
         * The single swap point for the client implementation. The fake is the default; debug builds seed
         * it with demo data so a fresh install goes through onboarding into a populated chat list.
         * [UniffiGimClientFactory] loads the native library only when it creates a client.
         */
        // TODO(gim-core): make the UniFFI-backed client the default once gim-core implements the API.
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
