package im.g.apps

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.fake.FakeGimClient

/**
 * Debug-only wrapper around a demo-seeded fake client that hides the demo session until [createIdentity], so
 * onboarding can run before the populated chat list shows. [createIdentity] signs in as the demo user and returns
 * a fixed demo recovery code; the entered display name is not used.
 */
internal class DemoAccountClient(
    private val inner: GimClient,
    @Volatile private var registered: Boolean,
) : GimClient by inner {
    override suspend fun session(): Session? = if (registered) inner.session() else null

    override fun userId(): String? = if (registered) inner.userId() else null

    override suspend fun createIdentity(displayName: String): String {
        if (registered) throw GimException.InvalidInput("already signed in")
        inner.session() ?: throw GimException.Internal("demo session missing")
        registered = true
        return FakeGimClient.fakeRecoveryCode(DEMO_USER_NUMBER)
    }

    override suspend fun signOut() {
        inner.signOut()
        registered = false
    }

    private companion object {
        const val DEMO_USER_NUMBER = 1
    }
}
