package im.g.apps.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceViewModelTest {
    @Test
    fun mode_follows_the_store() {
        val store = InMemoryThemeModeStore(ThemeMode.LIGHT)
        val vm = AppearanceViewModel(store)

        assertEquals(ThemeMode.LIGHT, vm.mode.value)
        store.set(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.mode.value)
    }

    @Test
    fun select_saves_the_mode() {
        val store = InMemoryThemeModeStore()
        val vm = AppearanceViewModel(store)

        vm.select(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.mode.value)
        assertEquals(ThemeMode.DARK, vm.mode.value)
    }
}
