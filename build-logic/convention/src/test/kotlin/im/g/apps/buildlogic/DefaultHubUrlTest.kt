package im.g.apps.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultHubUrlTest {
    @Test
    fun withoutOverrideUsesTheBuiltInHub() {
        assertEquals("https://hub.g.im", resolveDefaultHubUrl(null, null))
    }

    @Test
    fun propertyWinsOverEnv() {
        assertEquals("http://prop.test", resolveDefaultHubUrl("http://prop.test", "http://env.test"))
    }

    @Test
    fun blankPropertyFallsBackToEnv() {
        assertEquals("http://env.test", resolveDefaultHubUrl("  ", " http://env.test "))
    }

    @Test
    fun envOverridesTheBuiltInHub() {
        assertEquals("http://env.test", resolveDefaultHubUrl(null, "http://env.test"))
    }
}
