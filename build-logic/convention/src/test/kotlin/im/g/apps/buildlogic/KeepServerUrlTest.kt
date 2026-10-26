package im.g.apps.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepServerUrlTest {
    @Test
    fun withoutOverrideUsesTheBuiltInKeepServer() {
        assertEquals("https://keep.g.im", resolveKeepServerUrl(null, null))
    }

    @Test
    fun propertyWinsOverEnv() {
        assertEquals("http://prop.test", resolveKeepServerUrl(" http://prop.test ", "http://env.test"))
    }

    @Test
    fun envOverridesTheBuiltInKeepServer() {
        assertEquals("http://env.test", resolveKeepServerUrl(null, "http://env.test"))
    }

    @Test
    fun emptyOrOffTurnsItOff() {
        assertEquals("", resolveKeepServerUrl("", "http://env.test"))
        assertEquals("", resolveKeepServerUrl("off", null))
        assertEquals("", resolveKeepServerUrl(null, " OFF "))
        assertEquals("", resolveKeepServerUrl(null, ""))
    }
}
