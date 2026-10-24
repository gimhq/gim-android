package im.g.apps.feature.settings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val tags = listOf("en", "zh-CN", "zh-TW", "es")
    private val names = listOf("English", "简体中文", "繁體中文", "Español")
    private val options = tags.zip(names) { tag, name -> LanguageOption(tag, name) }

    @Test
    fun phone_language_is_checked_when_none_is_chosen() {
        rule.setContent { GimTheme { LanguageScreen(LanguageUiState(options, selected = null), onSelect = {}, onBack = {}) } }

        rule.onNodeWithText("Language").assertExists()
        rule.onNodeWithText("Use phone language").assertExists()
        rule.onNodeWithTag(LanguageTestTags.PHONE).assertIsSelected()
        for ((tag, name) in tags.zip(names)) {
            rule.onNodeWithText(name).assertExists()
            rule.onNodeWithTag(LanguageTestTags.option(tag)).assertIsNotSelected()
        }
    }

    @Test
    fun choosing_applies_the_language_and_checks_it() {
        val locales = InMemoryAppLocales()
        var back = 0
        rule.setContent {
            GimTheme { LanguageRoute(LanguageViewModel(locales, tags), onBack = { back++ }) }
        }

        for ((tag, name) in tags.zip(names)) {
            rule.onNodeWithText(name).performClick()

            assertEquals(tag, locales.current())
            rule.onNodeWithTag(LanguageTestTags.option(tag)).assertIsSelected()
        }
        rule.onNodeWithTag(LanguageTestTags.PHONE).assertIsNotSelected()
        rule.onNodeWithTag(LanguageTestTags.PHONE).performClick()
        assertNull(locales.current())
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(1, back)
    }
}
