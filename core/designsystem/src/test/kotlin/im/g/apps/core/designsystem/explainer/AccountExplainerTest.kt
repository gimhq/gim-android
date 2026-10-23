package im.g.apps.core.designsystem.explainer

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountExplainerTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun accountExplainer_showsAllPointTitles() {
        rule.setContent { GimTheme { AccountExplainer() } }
        rule.onNodeWithTag(AccountExplainerTestTags.ROOT).assertExists()
        AccountExplainerPoint.entries.forEach { point ->
            rule.onNodeWithText(context.getString(point.title), substring = true).assertExists()
        }
    }

    @Test
    fun accountExplainer_eachPointIsOneMergedNodeWithHeadingTitle() {
        rule.setContent { GimTheme { AccountExplainer() } }
        AccountExplainerPoint.entries.forEach { point ->
            val title = context.getString(point.title)
            val body = context.getString(point.body)
            val tag = AccountExplainerTestTags.point(point)
            assertEquals("account_explainer_point_${point.name.lowercase()}", tag)
            rule.onNodeWithTag(tag)
                .assert(hasText(title))
                .assert(hasText(body))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            rule.onNodeWithTag(tag, useUnmergedTree = true)
                .onChildren()
                .filterToOne(hasText(title))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun introPoints_areTheFirstThreeInOrder() {
        assertEquals(
            listOf(
                AccountExplainerPoint.NO_PHONE_NUMBER,
                AccountExplainerPoint.ON_YOUR_DEVICES,
                AccountExplainerPoint.LOCKED_MESSAGES,
            ),
            accountIntroPoints,
        )
    }
}
