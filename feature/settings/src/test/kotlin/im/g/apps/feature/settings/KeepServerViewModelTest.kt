package im.g.apps.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepServerViewModelTest {
    private val store = InMemoryKeepServerStore()

    private fun viewModel(buildDefault: String? = BUILT_IN, applied: String? = BUILT_IN) =
        KeepServerViewModel(store, buildDefault = buildDefault, applied = applied)

    @Test
    fun starts_with_the_current_server_in_the_field() {
        val state = viewModel().state.value

        assertEquals(BUILT_IN, state.current)
        assertEquals(BUILT_IN, state.input)
        assertEquals(KeepServerChoice.Default, state.choice)
        assertFalse(state.restartNeeded)
    }

    @Test
    fun save_stores_a_valid_url_and_asks_for_a_restart() {
        val vm = viewModel()

        vm.onInputChange(" https://keep.example.eu/ ")
        vm.save()

        assertEquals(KeepServerChoice.Custom(CUSTOM), store.choice.value)
        assertEquals(CUSTOM, vm.state.value.current)
        assertEquals(CUSTOM, vm.state.value.input)
        assertTrue(vm.state.value.restartNeeded)
    }

    @Test
    fun save_shows_the_error_and_keeps_the_choice_for_an_invalid_url() {
        val vm = viewModel()

        vm.onInputChange("http://keep.example.eu")
        vm.save()

        assertEquals(KeepServerUrlError.INSECURE, vm.state.value.error)
        assertEquals(KeepServerChoice.Default, store.choice.value)

        vm.onInputChange("https://keep.example.eu")
        assertNull(vm.state.value.error)
    }

    @Test
    fun saving_the_build_default_is_the_same_as_reset() {
        store.set(KeepServerChoice.Off)
        val vm = viewModel(applied = null)

        vm.onInputChange(BUILT_IN)
        vm.save()

        assertEquals(KeepServerChoice.Default, store.choice.value)
    }

    @Test
    fun reset_returns_to_the_build_default() {
        store.set(KeepServerChoice.Custom(CUSTOM))
        val vm = viewModel(applied = CUSTOM)

        vm.reset()

        assertEquals(KeepServerChoice.Default, store.choice.value)
        assertEquals(BUILT_IN, vm.state.value.current)
        assertEquals(BUILT_IN, vm.state.value.input)
        assertTrue(vm.state.value.restartNeeded)
    }

    @Test
    fun turn_off_stores_off_and_empties_the_current_server() {
        val vm = viewModel()

        vm.turnOff()

        assertEquals(KeepServerChoice.Off, store.choice.value)
        assertNull(vm.state.value.current)
        assertTrue(vm.state.value.restartNeeded)
    }

    @Test
    fun a_build_without_keep_resets_to_off() {
        store.set(KeepServerChoice.Custom(CUSTOM))
        val vm = viewModel(buildDefault = null, applied = CUSTOM)

        vm.reset()

        assertNull(vm.state.value.current)
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun back_to_the_running_server_needs_no_restart() {
        val vm = viewModel()

        vm.turnOff()
        vm.reset()

        assertFalse(vm.state.value.restartNeeded)
    }

    private companion object {
        const val BUILT_IN = "https://keep.g.im"
        const val CUSTOM = "https://keep.example.eu"
    }
}
