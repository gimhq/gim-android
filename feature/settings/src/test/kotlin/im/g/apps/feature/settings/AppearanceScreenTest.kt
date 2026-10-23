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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun shows_three_choices_with_the_current_one_checked() {
        rule.setContent { GimTheme { AppearanceScreen(mode = ThemeMode.SYSTEM, onSelect = {}, onBack = {}) } }

        rule.onNodeWithText("Appearance").assertExists()
        rule.onNodeWithText("Use phone setting").assertExists()
        rule.onNodeWithTag(AppearanceTestTags.SYSTEM).assertIsSelected()
        rule.onNodeWithTag(AppearanceTestTags.LIGHT).assertIsNotSelected()
        rule.onNodeWithTag(AppearanceTestTags.DARK).assertIsNotSelected()
    }

    @Test
    fun choosing_saves_the_mode_and_checks_it() {
        val store = InMemoryThemeModeStore()
        var back = 0
        rule.setContent { GimTheme { AppearanceRoute(AppearanceViewModel(store), onBack = { back++ }) } }

        rule.onNodeWithText("Dark").performClick()

        assertEquals(ThemeMode.DARK, store.mode.value)
        rule.onNodeWithTag(AppearanceTestTags.DARK).assertIsSelected()
        rule.onNodeWithTag(AppearanceTestTags.SYSTEM).assertIsNotSelected()
        rule.onNodeWithTag(AppearanceTestTags.LIGHT).performClick()
        assertEquals(ThemeMode.LIGHT, store.mode.value)
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(1, back)
    }
}
