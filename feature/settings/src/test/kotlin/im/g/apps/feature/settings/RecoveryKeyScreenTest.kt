package im.g.apps.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.explainer.AccountExplainerPoint
import im.g.apps.core.designsystem.explainer.AccountExplainerTestTags
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryKeyScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun explains_the_recovery_key_and_how_to_keep_it() {
        var back = 0
        rule.setContent { GimTheme { RecoveryKeyScreen(onBack = { back++ }) } }

        rule.onNodeWithText("Your recovery key").assertExists()
        rule.onNodeWithTag(AccountExplainerTestTags.point(AccountExplainerPoint.RECOVERY_KEY)).assertExists()
        rule.onNodeWithText("Your recovery key is a spare key", substring = true).assertExists()
        rule.onNodeWithText("new phone", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Not even gim", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("recovery kit somewhere safe", substring = true).performScrollTo().assertExists()
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(1, back)
    }
}
