package im.g.apps.core.designsystem.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnreadBadgeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun counts_above_99_show_99_plus() {
        rule.setContent { GimTheme { UnreadBadge(count = 100) } }
        rule.onNodeWithText("99+").assertExists()
        rule.onNodeWithText("100").assertDoesNotExist()
    }

    @Test
    fun count_of_99_is_shown_as_is() {
        rule.setContent { GimTheme { UnreadBadge(count = 99) } }
        rule.onNodeWithText("99").assertExists()
    }

    @Test
    fun badge_without_description_only_exposes_its_text() {
        rule.setContent { GimTheme { UnreadBadge(count = 150, describe = false) } }
        rule.onNodeWithText("99+").assertExists()
        rule.onNodeWithContentDescription("More than 99 unread messages").assertDoesNotExist()
    }

    @Test
    fun single_digit_badge_keeps_minimum_size() {
        rule.setContent { GimTheme { UnreadBadge(count = 1, modifier = Modifier.testTag("badge")) } }
        rule.onNodeWithTag("badge").assertWidthIsAtLeast(20.dp).assertHeightIsAtLeast(20.dp)
    }
}
