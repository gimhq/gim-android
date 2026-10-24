package im.g.apps.feature.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeModeStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(SharedPrefsThemeModeStore.PREFS, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaults_to_the_phone_setting() {
        assertEquals(ThemeMode.SYSTEM, SharedPrefsThemeModeStore(context).mode.value)
    }

    @Test
    fun set_updates_the_mode_and_survives_a_new_store() {
        val store = SharedPrefsThemeModeStore(context)

        store.set(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.mode.value)
        assertEquals(ThemeMode.DARK, SharedPrefsThemeModeStore(context).mode.value)
    }

    @Test
    fun unknown_stored_value_is_the_phone_setting() {
        prefs.edit().putString(SharedPrefsThemeModeStore.KEY, "SEPIA").commit()

        assertEquals(ThemeMode.SYSTEM, SharedPrefsThemeModeStore(context).mode.value)
    }

    @Test
    fun in_memory_store_starts_at_the_given_mode() {
        val store = InMemoryThemeModeStore(ThemeMode.LIGHT)

        store.set(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.mode.value)
    }

    @Test
    fun maps_to_the_appcompat_night_mode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.toNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.toNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.toNightMode())
    }
}
