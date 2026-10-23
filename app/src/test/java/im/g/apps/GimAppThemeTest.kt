package im.g.apps

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.feature.settings.InMemoryThemeModeStore
import im.g.apps.feature.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GimAppThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private var surface = Color.Unspecified
    private var darkTheme: Boolean? = null

    private fun show(store: InMemoryThemeModeStore) = rule.setContent {
        GimAppTheme(store, onDarkThemeChanged = { darkTheme = it }) { surface = MaterialTheme.colorScheme.surface }
    }

    @Test
    fun dark_mode_renders_dark_material_colors() {
        show(InMemoryThemeModeStore(ThemeMode.DARK))

        rule.runOnIdle {
            assertTrue(surface.luminance() < 0.5f)
            assertEquals(true, darkTheme)
        }
    }

    @Test
    fun light_mode_renders_light_colors_and_follows_changes() {
        val store = InMemoryThemeModeStore(ThemeMode.LIGHT)
        show(store)

        rule.runOnIdle {
            assertTrue(surface.luminance() > 0.5f)
            assertEquals(false, darkTheme)
        }
        store.set(ThemeMode.DARK)
        rule.runOnIdle {
            assertTrue(surface.luminance() < 0.5f)
            assertEquals(true, darkTheme)
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun phone_setting_follows_the_system_night_mode() {
        show(InMemoryThemeModeStore(ThemeMode.SYSTEM))

        rule.runOnIdle { assertEquals(true, darkTheme) }
    }
}
