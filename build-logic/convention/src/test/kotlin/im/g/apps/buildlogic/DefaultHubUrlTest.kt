package im.g.apps.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultHubUrlTest {
    @Test
    fun releaseWithoutOverrideUsesProductHub() {
        assertEquals("https://hub.g.im", resolveDefaultHubUrl(null, null, release = true))
    }

    @Test
    fun debugWithoutOverrideIsEmpty() {
        assertEquals("", resolveDefaultHubUrl(null, null, release = false))
    }

    @Test
    fun propertyWinsOverEnv() {
        assertEquals("http://prop.test", resolveDefaultHubUrl("http://prop.test", "http://env.test", release = false))
    }

    @Test
    fun blankPropertyFallsBackToEnv() {
        assertEquals("http://env.test", resolveDefaultHubUrl("  ", " http://env.test ", release = false))
    }

    @Test
    fun overrideWinsInRelease() {
        assertEquals("http://prop.test", resolveDefaultHubUrl("http://prop.test", null, release = true))
        assertEquals("http://env.test", resolveDefaultHubUrl(null, "http://env.test", release = true))
    }
}
