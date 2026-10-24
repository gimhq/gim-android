package im.g.apps.feature.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageViewModelTest {
    private val tags = listOf("en", "zh-CN", "zh-TW", "es")

    @Test
    fun options_name_each_tag_in_its_own_language() {
        val vm = LanguageViewModel(InMemoryAppLocales(), tags)

        assertEquals(
            listOf(
                LanguageOption("en", "English"),
                LanguageOption("zh-CN", "简体中文"),
                LanguageOption("zh-TW", "繁體中文"),
                LanguageOption("es", "Español"),
            ),
            vm.state.value.options,
        )
    }

    @Test
    fun chinese_is_named_by_script() {
        assertEquals("简体中文", languageDisplayName("zh-Hans"))
        assertEquals("简体中文", languageDisplayName("zh-Hans-CN"))
        assertEquals("繁體中文", languageDisplayName("zh-Hant"))
        assertEquals("繁體中文", languageDisplayName("zh-Hant-HK"))
    }

    @Test
    fun select_stores_each_tag() {
        val locales = InMemoryAppLocales()
        val vm = LanguageViewModel(locales, tags)

        for (tag in tags) {
            vm.select(tag)
            assertEquals(tag, locales.current())
            assertEquals(tag, vm.state.value.selected)
        }
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
        assertEquals(tags, supportedLanguageTags(ApplicationProvider.getApplicationContext()))
    }
}
