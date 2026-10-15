package im.g.apps

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.Session

/**
 * Debug-only wrapper around a demo-seeded fake client that hides the demo session until [register], so
 * onboarding can run before the populated chat list shows. [register] signs in as the demo user; the
 * entered handle and display name are not used.
 */
internal class DemoAccountClient(
    private val inner: GimClient,
    @Volatile private var registered: Boolean,
) : GimClient by inner {
    override suspend fun session(): Session? = if (registered) inner.session() else null

    override suspend fun register(handle: String, displayName: String): Session {
        if (registered) throw GimException.InvalidInput("already registered")
        val session = inner.session() ?: throw GimException.Internal("demo session missing")
        registered = true
        return session
    }

    override suspend fun signOut() {
        inner.signOut()
        registered = false
    }
}
