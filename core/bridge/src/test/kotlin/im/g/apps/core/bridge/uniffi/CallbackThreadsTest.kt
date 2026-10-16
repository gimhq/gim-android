package im.g.apps.core.bridge.uniffi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Reflection over the generated bindings only; nothing here loads JNA's or gim-core's native library.
class CallbackThreadsTest {
    private val bindings = File("src/main/kotlin/im/g/gimcore/gim_core.kt").readText()

    @Test
    fun coversEveryGeneratedCallbackInterface() {
        val generated = Regex("""^internal object (uniffiCallbackInterface\w+) \{""", RegexOption.MULTILINE)
            .findAll(bindings).map { it.groupValues[1] }.toSet()

        assertEquals(generated, CallbackThreads.vtableHolders.map { it.simpleName }.toSet())
    }

    @Test
    fun coversEveryVtableCallbackAndTheFutureContinuation() {
        val vtableCallbacks = Regex("""^ {4}internal object .*: UniffiCallbackInterface""", RegexOption.MULTILINE)
            .findAll(bindings).count()
        val callbacks = CallbackThreads.callbacks()

        assertEquals(vtableCallbacks + 1, callbacks.toSet().size)
        assertTrue(im.g.gimcore.uniffiRustFutureContinuationCallbackImpl in callbacks)
        assertTrue(im.g.gimcore.uniffiCallbackInterfacePlatformHooks.`secureStoreGet` in callbacks)
    }
}
