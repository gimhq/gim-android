package im.g.apps.feature.chatlist

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch targets, labels, text overflow and TalkBack order of the chat list body at 1x and 2x font scale.
 * The top bar is covered by its own screen tests. Native graphics for real text measurement.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ChatListAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    /** Enough rows to reach under the FAB; odd rows have long titles and unread counts (up to 99+). */
    private val rows = (1..8).map { i ->
        ChatRowUi(
            id = "chat-$i",
            title = if (i % 2 == 1) "Weekend hiking crew $i with a very long group name" else "Ana Berg $i",
            avatarPath = null,
            sender = if (i % 3 == 0) PreviewSender.You else PreviewSender.Member("Nikos"),
            preview = "Table booked for eight at the harbour, bring the charts",
            time = if (i % 2 == 1) ChatTimeLabel.Minutes(i) else ChatTimeLabel.Yesterday,
            unreadCount = if (i % 2 == 1) i * 30 else 0,
        )
    }

    private fun show(state: ChatListUiState) {
        rule.setContent {
            view = LocalView.current
            SideEffect { enableTalkBack(view) }
            GimTheme {
                ChatListScreen(
                    state = state,
                    onOpenChat = {},
                    onNewChat = {},
                    onNewGroup = {},
                    onSignOut = {},
                    onLinkDevice = {},
                )
            }
        }
    }

    @Test
    fun rows_meetTheBaseline() = checkRows()

    @Test
    @Config(fontScale = 2.0f)
    fun rows_meetTheBaselineAtDoubleFontScale() = checkRows()

    @Test
    fun emptyState_meetsTheBaseline() = checkEmpty()

    @Test
    @Config(fontScale = 2.0f)
    fun emptyState_meetsTheBaselineAtDoubleFontScale() = checkEmpty()

    private fun checkRows() {
        show(ChatListUiState(rows = rows.toImmutableList()))
        val shown = rows.filter { rule.onAllNodes(rowMatcher(it)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("only ${shown.size} rows composed", shown.size >= 3)
        val bodyTop = rule.onNode(rowMatcher(rows.first())).fetchSemanticsNode().boundsInRoot.top

        assertBaseline(bodyTop) { text ->
            // Titles and previews are single-line by design and ellipsize.
            rows.any { text == it.title || text.endsWith(it.preview) }
        }

        val order = talkBackOrder()
        val rowPositions = shown.map { order.positionOf(rule.onNode(rowMatcher(it)).fetchSemanticsNode(), it.title) }
        assertEquals("rows out of list order", rowPositions.sorted(), rowPositions)
        val fab = order.positionOf(rule.onNode(hasContentDescription("New message")).fetchSemanticsNode(), "FAB")
        assertTrue("FAB at $fab before the last row (rows at $rowPositions)", fab > rowPositions.last())
    }

    private fun checkEmpty() {
        show(ChatListUiState(isEmpty = true))
        val bodyTop = rule.onNodeWithText("No chats yet").fetchSemanticsNode().boundsInRoot.top

        assertBaseline(bodyTop) { false }

        val order = talkBackOrder()
        val title = order.positionOf(rule.onNodeWithText("No chats yet").fetchSemanticsNode(), "title")
        val start = order.positionOf(rule.onNode(hasClickAction() and hasText("Start a chat")).fetchSemanticsNode(), "start")
        val fab = order.positionOf(rule.onNode(hasContentDescription("New message")).fetchSemanticsNode(), "FAB")
        assertTrue("empty state order title $title, start $start, FAB $fab", title < start && start < fab)
    }

    private fun rowMatcher(row: ChatRowUi) = hasClickAction() and hasText(row.title)

    /**
     * Nodes below [bodyTop] (the top bar is out of scope): every clickable node has a 48 dp touch area,
     * every clickable or image node is labelled, and no text is clipped or ellipsized unless [mayEllipsize].
     */
    private fun assertBaseline(bodyTop: Float, mayEllipsize: (String) -> Boolean) {
        fun SemanticsNode.inBody() = boundsInRoot.top >= bodyTop - 0.5f
        val minPx = with(rule.density) { 48.dp.toPx() } - 1f

        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.inBody() }
        assertTrue("no clickable nodes", clickable.isNotEmpty())
        clickable.forEach {
            val touch = it.touchBoundsInRoot
            assertTrue("'${it.label()}' touch area $touch", touch.width >= minPx && touch.height >= minPx)
        }

        rule.onAllNodes(hasClickAction() or isImage()).fetchSemanticsNodes().filter { it.inBody() }.forEach {
            assertTrue("unlabelled node ${it.config}", it.label().isNotBlank())
        }

        val texts = rule.onAllNodes(hasTextLayout(), useUnmergedTree = true).fetchSemanticsNodes().filter { it.inBody() }
        assertTrue("no text nodes", texts.isNotEmpty())
        texts.forEach { node ->
            val text = node.label()
            val layout = node.textLayout()
            // didOverflowHeight is also true for text cut at maxLines, so compare the laid-out lines with the box.
            assertTrue("'$text' clipped vertically: ${layout.size}", layout.multiParagraph.height <= layout.size.height + 0.5f)
            val cut = layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            if (cut) {
                assertTrue("'$text' ellipsized", mayEllipsize(text))
                assertEquals("'$text' ellipsized on several lines", 1, layout.lineCount)
            }
        }
    }

    private fun isImage() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)

    private fun hasTextLayout() = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    private fun SemanticsNode.label(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /**
     * Compose computes the TalkBack order only while a screen reader is on; force that on [view]'s
     * accessibility delegate before TalkBack queries it, as Compose's own tests do. The platform manager
     * is switched on too, so the events Compose then sends are accepted.
     */
    private fun enableTalkBack(view: View) {
        shadowOf(view.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val delegate = view.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(view)
        delegate.javaClass
            .getMethod("setAccessibilityForceEnabledForTesting\$ui_release", Boolean::class.javaPrimitiveType)
            .invoke(delegate, true)
    }

    /** Semantics ids in the order TalkBack visits them, from the traversal-before links Compose exposes. */
    private fun talkBackOrder(): List<Int> {
        val provider = checkNotNull(view.accessibilityNodeProvider) { "no accessibility node provider" }
        fun next(id: Int): Int? {
            val info = AccessibilityNodeInfo.obtain()
            provider.addExtraDataToAccessibilityNodeInfo(id, info, TRAVERSAL_BEFORE, null)
            return info.extras.getInt(TRAVERSAL_BEFORE, View.NO_ID).takeIf { it != View.NO_ID }
        }
        val ids = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().map { it.id }
        val links = ids.mapNotNull { id -> next(id)?.let { id to it } }.toMap()
        // The chain starts at the one node nothing points to.
        var current: Int? = links.keys.singleOrNull { it !in links.values }
        val order = mutableListOf<Int>()
        while (current != null && current !in order) {
            order += current
            current = links[current] ?: next(current)
        }
        assertTrue("no TalkBack traversal order", order.size > 1)
        return order
    }

    private fun List<Int>.positionOf(node: SemanticsNode, name: String): Int =
        indexOf(node.id).also { assertTrue("$name not in the TalkBack order", it >= 0) }

    private companion object {
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
    }
}
