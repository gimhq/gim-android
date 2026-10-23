package im.g.apps.core.bridge.uniffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// The mirror's mutedForever() (FakeGimClient's too) is Long.MAX_VALUE; gim-core's value must map onto it and back.
@RunWith(AndroidJUnit4::class)
class MutedForeverTest {
    @Test
    fun native_muted_forever_maps_to_long_max_and_back() {
        val native = im.g.gimcore.mutedForever()
        assertEquals(Long.MAX_VALUE, native.toMirrorMutedUntil())
        assertEquals(native, Long.MAX_VALUE.toCoreMutedUntil())
    }
}
