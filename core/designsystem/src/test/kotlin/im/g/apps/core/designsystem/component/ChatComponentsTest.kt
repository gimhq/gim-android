package im.g.apps.core.designsystem.component

import android.graphics.Region
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ChatComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun dateSeparator_isOneHeadingNode() {
        rule.setContent { GimTheme { DateSeparator(label = "Today") } }
        rule.onNodeWithText("Today").assert(isHeading())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun groupedBubble_hasASmallCornerOnTheRunSide() {
        // Inbound, left to right: the run side is the left. Near the top-left corner, inside the tail gutter
        // (7 px), a 4 px corner is filled while the 18 px corner of an ungrouped bubble is not.
        fun filledNearTopLeft(groupedWithPrevious: Boolean): Boolean {
            val shape = BubbleShape(outgoing = false, groupedWithPrevious = groupedWithPrevious, groupedWithNext = true)
            val outline = shape.createOutline(Size(200f, 100f), LayoutDirection.Ltr, Density(1f)) as Outline.Generic
            val region = Region()
            region.setPath(outline.path.asAndroidPath(), Region(0, 0, 200, 100))
            return region.contains(9, 2)
        }
        assertTrue(filledNearTopLeft(groupedWithPrevious = true))
        assertFalse(filledNearTopLeft(groupedWithPrevious = false))
    }

    @Test
    fun reactionChips_haveA48dpTouchTarget() {
        rule.setContent {
            GimTheme { ReactionChips(reactions = listOf(ReactionChipModel("👍", 2, mine = true)), onClick = {}) }
        }
        val chip = rule.onNodeWithContentDescription("👍, 2 reactions, including yours").assertIsSelected()
        // The chip draws 32 dp high; its touch area is what must reach 48 dp.
        val touch = chip.fetchSemanticsNode().touchBoundsInRoot
        val minPx = with(rule.density) { 48.dp.toPx() } - 1f
        assertTrue("touch area $touch", touch.height >= minPx && touch.width >= minPx)
    }

    @Test
    fun statusTicks_haveNoOwnDescription_theLabelIsForTheRow() {
        rule.setContent { GimTheme { StatusTicks(state = TickState.READ) } }
        rule.onNodeWithContentDescription("Read").assertDoesNotExist()
    }
}
