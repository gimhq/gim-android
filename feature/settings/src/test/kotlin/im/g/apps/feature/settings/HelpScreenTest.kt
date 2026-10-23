package im.g.apps.feature.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.explainer.AccountExplainerTestTags
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun shows_how_gim_works_and_both_versions() {
        rule.setContent { GimTheme { HelpScreen(appVersion = "1.2.3", sdkVersion = "0.1.0+c1b7503", onBack = {}, onLicences = {}) } }

        rule.onNodeWithText("Help and about").assertExists()
        rule.onNodeWithText("How gim works").assertExists()
        rule.onNodeWithTag(AccountExplainerTestTags.ROOT).assertExists()
        rule.onNodeWithText("1.2.3").performScrollTo().assertExists()
        rule.onNodeWithText("0.1.0+c1b7503").performScrollTo().assertExists()
    }

    @Test
    fun licences_row_and_back_call_their_callbacks() {
        val calls = mutableListOf<String>()
        rule.setContent {
            GimTheme {
                HelpScreen(appVersion = "1", sdkVersion = "2", onBack = { calls += "back" }, onLicences = { calls += "licences" })
            }
        }

        rule.onNodeWithTag(HelpTestTags.LICENCES).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf("licences", "back"), calls)
    }

    @Test
    fun licences_list_names_each_library_and_its_licence() {
        var back = 0
        rule.setContent { GimTheme { LicencesScreen(onBack = { back++ }) } }

        rule.onNodeWithText("Open-source licences").assertExists()
        rule.onNodeWithText("AndroidX and Jetpack Compose").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("JNA"))
        rule.onNodeWithText("Apache 2.0 / LGPL 2.1").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("gim-core"))
        rule.onNodeWithText("gim client SDK").assertExists()
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(1, back)
    }
}
