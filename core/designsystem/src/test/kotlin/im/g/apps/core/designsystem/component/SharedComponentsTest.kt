package im.g.apps.core.designsystem.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class SharedComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun topBar_showsTitleAndSubtitle() {
        rule.setContent { GimTheme { GimTopBar(title = "Ada Lovelace", subtitle = "Online") } }
        rule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        rule.onNodeWithText("Online").assertIsDisplayed()
    }

    @Test
    fun topBar_titleClickInvokesCallback() {
        var clicks = 0
        rule.setContent {
            GimTheme { GimTopBar(title = "Ada Lovelace", subtitle = "Online", onTitleClick = { clicks++ }) }
        }
        rule.onNodeWithText("Ada Lovelace")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun topBarIconButton_hasDescriptionAndTouchTarget() {
        var clicks = 0
        rule.setContent {
            GimTheme {
                GimTopBar(
                    title = "Chats",
                    navigationIcon = {
                        GimTopBarIconButton(onClick = { clicks++ }, contentDescription = "Back") {
                            CloseGlyph(color = Color.Black)
                        }
                    },
                )
            }
        }
        // assertTouch*IsAtLeast is not in ui-test 1.7; the button's own bounds are the touch target here.
        rule.onNodeWithContentDescription("Back")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun confirmDialog_buttonsInvokeCallbacks() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            GimTheme {
                GimConfirmDialog(
                    title = "Delete message?",
                    body = "It will be removed for everyone.",
                    confirmLabel = "Delete",
                    dismissLabel = "Keep",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    destructive = true,
                )
            }
        }
        rule.onNodeWithText("It will be removed for everyone.").assertExists()
        rule.onNodeWithText("Delete").assertIsEnabled().performClick()
        rule.onNodeWithText("Keep").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun confirmDialog_disabled_disablesButtons() {
        rule.setContent {
            GimTheme {
                GimConfirmDialog(
                    title = "Sign out?",
                    body = null,
                    confirmLabel = "Sign out",
                    dismissLabel = "Cancel",
                    onConfirm = {},
                    onDismiss = {},
                    enabled = false,
                )
            }
        }
        rule.onNodeWithText("Sign out").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun snackbarHost_showsMessage() {
        val hostState = SnackbarHostState()
        val message = "Couldn't send. Check your connection and try again."
        rule.setContent {
            GimTheme {
                GimSnackbarHost(hostState = hostState)
                LaunchedEffect(Unit) { hostState.showSnackbar(message) }
            }
        }
        rule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2.0f)
    // Real text measurement; the legacy Robolectric graphics mode reports fake text widths.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun topBar_atDoubleFontScale_growsInsteadOfClipping() {
        rule.setContent { GimTheme { GimTopBar(title = "Ada", subtitle = "Online", modifier = Modifier.testTag("bar")) } }
        rule.onNodeWithText("Ada").assertIsDisplayed()
        rule.onNodeWithText("Online").assertIsDisplayed()
        val node = rule.onNodeWithText("Ada", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val bar = rule.onNodeWithTag("bar").getBoundsInRoot()
        val layout = layouts.single()
        // hasVisualOverflow is always true for ellipsized text in Compose 1.7: the paragraph takes the full
        // max width while the node wraps the text. Check the parts that mean clipping instead.
        assertFalse("title clipped vertically: ${layout.size} in bar $bar", layout.didOverflowHeight)
        assertFalse("title ellipsized: ${layout.size} in bar $bar", layout.isLineEllipsized(0))
        val title = rule.onNodeWithText("Ada", useUnmergedTree = true).getBoundsInRoot()
        val subtitle = rule.onNodeWithText("Online", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("title clipped: $title outside $bar", title.top >= bar.top)
        assertTrue("subtitle clipped: $subtitle outside $bar", subtitle.bottom <= bar.bottom)
    }
}
