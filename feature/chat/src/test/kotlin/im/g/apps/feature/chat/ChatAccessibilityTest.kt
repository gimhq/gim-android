package im.g.apps.feature.chat

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.component.ReactionChipModel
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.chat.composer.COMPOSER_INPUT_TEST_TAG
import im.g.apps.feature.chat.composer.Composer
import im.g.apps.feature.chat.composer.ComposerUiState
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.DateSeparatorRow
import im.g.apps.feature.chat.timeline.MessageRow
import im.g.apps.feature.chat.timeline.ReplyPreviewUi
import im.g.apps.feature.chat.timeline.SenderUi
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch targets, labels, text overflow and TalkBack order of the chat screen with the real composer, at 1x
 * and 2x font scale. Native graphics for real text measurement.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ChatAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    private val nikos = SenderUi("u-nikos", "Nikos Papas")

    private fun message(id: String, body: String, time: String) = MessageRow(
        key = id,
        contentType = "message-text",
        outgoing = false,
        senderName = null,
        body = body,
        image = null,
        file = null,
        replyPreview = null,
        reactions = persistentListOf(),
        edited = false,
        deleted = false,
        unsupported = false,
        tick = null,
        time = time,
        groupedWithPrevious = false,
        groupedWithNext = false,
    )

    /** Newest first: outbound with reactions, an inbound run (the last one quotes a reply), a date chip. */
    private val items = persistentListOf(
        message("m4", "Perfect", "12:03").copy(
            outgoing = true,
            tick = TickState.READ,
            reactions = persistentListOf(ReactionChipModel("👍", 2, mine = true), ReactionChipModel("⚓", 1, mine = false)),
        ),
        message("m3", "Bring the charts", "12:02").copy(
            sender = nikos,
            groupedWithPrevious = true,
            replyPreview = ReplyPreviewUi("You", "Shall we leave Split at nine tomorrow morning?"),
        ),
        message("m2", "Wind looks good", "12:01").copy(sender = nikos, senderName = "Nikos Papas", groupedWithNext = true),
        DateSeparatorRow("date-today", UiText.Raw("Today")),
    )

    private fun show() {
        rule.setContent {
            view = LocalView.current
            remember(view) { enableTalkBack(view) }
            GimTheme {
                ChatScreen(
                    state = ChatUiState(
                        header = ChatHeaderUi(title = "Aegean Crew", subtitle = UiText.Raw("3 members"), isGroup = true),
                        items = items,
                        hasMoreOlder = false,
                    ),
                    composer = {
                        Composer(
                            state = ComposerUiState(),
                            onDraftChange = {},
                            onSend = {},
                            onCancelMode = {},
                            onAttachPhoto = {},
                            onAttachFile = {},
                        )
                    },
                    onBack = {},
                    onOpenGroupInfo = {},
                    onLoadOlder = {},
                    onVisible = {},
                    onRetry = {},
                    onImageNeeded = {},
                    onOpenFile = {},
                    onMessageLongPress = {},
                    onReply = {},
                    onToggleReaction = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun chat_meetsTheBaseline() = check()

    @Test
    @Config(fontScale = 2.0f)
    fun chat_meetsTheBaselineAtDoubleFontScale() = check()

    private fun check() {
        show()

        // Single-line by design: the top bar and the quoted reply ellipsize.
        assertBaseline(setOf("Aegean Crew", "3 members", "You", "Shall we leave Split at nine tomorrow morning?"))

        // The header avatar is decorative; TalkBack must not stop on its initials.
        rule.onAllNodesWithText("AC").assertCountEquals(0)

        val order = talkBackOrder()
        fun position(name: String, node: SemanticsNodeInteraction) = order.positionOf(node.fetchSemanticsNode(), name)
        val topBar = listOf(
            position("back", rule.onNode(hasContentDescription("Go back"))),
            position("title", rule.onNode(hasText("Aegean Crew"))),
            position("group info", rule.onNode(hasContentDescription("Group info"))),
        )
        val timeline = buildList {
            listOf("Wind looks good", "Bring the charts", "Perfect").forEach { body ->
                val bubble = rule.onAllNodes(hasClickAction() and hasText(body)).fetchSemanticsNodes()
                bubble.firstOrNull()?.let { add(order.positionOf(it, body)) }
            }
            rule.onAllNodes(isHeading()).fetchSemanticsNodes().forEach { add(order.positionOf(it, "date chip")) }
            rule.onAllNodes(hasContentDescription("Replying to", substring = true)).fetchSemanticsNodes()
                .forEach { add(order.positionOf(it, "reply preview")) }
            rule.onAllNodes(hasContentDescription("reaction", substring = true)).fetchSemanticsNodes()
                .forEach { add(order.positionOf(it, "reaction")) }
        }
        assertTrue("only ${timeline.size} timeline nodes shown", timeline.size >= 3)
        val attach = position("attach", rule.onNode(hasContentDescription("Attach a photo or file")))
        val input = position("input", rule.onNodeWithTag(COMPOSER_INPUT_TEST_TAG))
        val send = position("send", rule.onNode(hasContentDescription("Send message")))

        assertTrue("top bar $topBar not before timeline $timeline", topBar.max() < timeline.min())
        assertTrue("timeline $timeline not before composer $attach", timeline.max() < attach)
        assertEquals("composer order", listOf(attach, input, send).sorted(), listOf(attach, input, send))
    }

    /**
     * Every clickable node has a 48 dp touch area, every clickable or image node is labelled, and no text is
     * clipped or ellipsized unless it is one of the single-line [mayEllipsize] texts.
     */
    private fun assertBaseline(mayEllipsize: Set<String>) {
        val minPx = with(rule.density) { 48.dp.toPx() } - 1f

        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("no clickable nodes", clickable.isNotEmpty())
        clickable.forEach {
            val touch = it.touchBoundsInRoot
            assertTrue("'${it.label()}' touch area $touch", touch.width >= minPx && touch.height >= minPx)
        }

        rule.onAllNodes(hasClickAction() or isImage()).fetchSemanticsNodes().forEach {
            assertTrue("unlabelled node ${it.config}", it.label().isNotBlank())
        }

        val texts = rule.onAllNodes(hasTextLayout(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no text nodes", texts.isNotEmpty())
        texts.forEach { node ->
            val text = node.label()
            val layout = node.textLayout()
            // didOverflowHeight is also true for text cut at maxLines, so compare the laid-out lines with the box.
            assertTrue("'$text' clipped vertically: ${layout.size}", layout.multiParagraph.height <= layout.size.height + 0.5f)
            val cut = layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            if (cut) {
                assertTrue("'$text' ellipsized", text in mayEllipsize)
                assertEquals("'$text' ellipsized on several lines", 1, layout.lineCount)
            }
        }
    }

    private fun isImage() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)

    private fun hasTextLayout() = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    private fun SemanticsNode.label(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)).joinToString(" ").trim()

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /**
     * Compose computes the TalkBack order only while a screen reader is on; force that on [view]'s
     * accessibility delegate before the first semantics pass, as Compose's own tests do. The platform manager
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
