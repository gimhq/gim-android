package im.g.apps.feature.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageViewModelTest {
    @Test
    fun options_name_each_tag_in_its_own_language() {
        val vm = LanguageViewModel(InMemoryAppLocales(), listOf("en", "es"))

        assertEquals(
            listOf(LanguageOption("en", "English"), LanguageOption("es", "Español")),
            vm.state.value.options,
        )
    }

    @Test
    fun selection_starts_at_the_current_app_language() {
        assertEquals("es", LanguageViewModel(InMemoryAppLocales("es"), listOf("en", "es")).state.value.selected)
        assertNull(LanguageViewModel(InMemoryAppLocales(), listOf("en", "es")).state.value.selected)
    }

    @Test
    fun select_applies_the_language() {
        val locales = InMemoryAppLocales()
        val vm = LanguageViewModel(locales, listOf("en", "es"))

        vm.select("es")

        assertEquals("es", locales.current())
        assertEquals("es", vm.state.value.selected)
    }

    @Test
    fun phone_language_sets_null() {
        val locales = InMemoryAppLocales("es")
        val vm = LanguageViewModel(locales, listOf("en", "es"))

        vm.select(null)

        assertNull(locales.current())
        assertNull(vm.state.value.selected)
    }

    @Test
    fun supported_tags_come_from_the_resource_list() {
        assertEquals(listOf("en"), supportedLanguageTags(ApplicationProvider.getApplicationContext()))
    }
}
