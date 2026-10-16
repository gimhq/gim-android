package im.g.apps.core.bridge.uniffi

import com.sun.jna.Callback
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Native

/**
 * Keeps gim-core's native (tokio) threads attached to the VM once a JNA callback has attached them.
 *
 * By default JNA attaches a native thread for a callback and detaches it when that callback returns, even when
 * the callback was nested in another one on the same thread. gim-core nests callbacks: a future continuation
 * resumed on an unconfined dispatcher polls the future again inline, and that poll calls the platform hooks (or a
 * listener, or a handle clone/free). The inner callback's detach then runs with the outer callback's frames on the
 * stack and ART aborts with "attempting to detach while still running code".
 *
 * A [CallbackThreadInitializer] with `detach = false` on every gim-core callback object makes JNA leave the thread
 * attached. It stays attached until the native thread exits, when JNA's thread-local destructor detaches it, so the
 * number of attached threads is bounded by tokio's thread pools. JNA reads the initializer only when it first builds
 * a callback's native trampoline, so [install] must run before anything touches the UniFFI bindings.
 */
internal object CallbackThreads {
    private val keepAttached = CallbackThreadInitializer(true, false, "gim-core")

    /** Objects holding the generated vtable callbacks, one per gim-core callback interface. */
    internal val vtableHolders: List<Class<*>> = listOf(
        im.g.gimcore.uniffiCallbackInterfaceChatListListener::class.java,
        im.g.gimcore.uniffiCallbackInterfaceConnectionStateListener::class.java,
        im.g.gimcore.uniffiCallbackInterfaceHubListListener::class.java,
        im.g.gimcore.uniffiCallbackInterfaceLocalTimeZone::class.java,
        im.g.gimcore.uniffiCallbackInterfacePlatformHooks::class.java,
        im.g.gimcore.uniffiCallbackInterfaceTimelineListener::class.java,
        im.g.gimcore.uniffiCallbackInterfaceTypingListener::class.java,
    )

    private val installed: Unit by lazy {
        callbacks().forEach { Native.setCallbackThreadInitializer(it, keepAttached) }
    }

    /** Registers the initializer; idempotent. */
    fun install() = installed

    /** Every callback object gim-core's bindings hand to native code. Loads no native library. */
    internal fun callbacks(): List<Callback> =
        listOf<Callback>(im.g.gimcore.uniffiRustFutureContinuationCallbackImpl) +
            vtableHolders.flatMap { holder ->
                holder.declaredClasses
                    .filter { Callback::class.java.isAssignableFrom(it) }
                    .map { it.getField("INSTANCE").get(null) as Callback }
            }
}
