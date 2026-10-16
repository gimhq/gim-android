package im.g.apps.testing

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Hides the soft keyboard, waits until it is gone and the layout has settled, then clicks the enabled, displayed
 * node tagged [tag] (scrolled into view when it sits in a scrollable container).
 *
 * On a real phone the IME hide animation (MIUI: ~0.5 s) is still resizing the `imePadding` content when
 * `waitForIdle` returns; a click injected then was seen to be dropped (no `onClick`, the screen stays put). So the
 * click waits until the IME reports hidden and the bounds of the node and all of its ancestors (which include the
 * IME-padded scroll container) have not changed for [SETTLE_MS].
 *
 * With [next], a click that left the node in place and enabled while [next] did not appear within [RETRY_AFTER_MS]
 * is retried once, logged under [logTag]. Only pass [next] for local transitions: a click that registered always
 * removes or disables the node, so the retry never repeats an action.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.clickWhenSettled(
    logTag: String,
    button: String,
    tag: String,
    timeoutMs: Long,
    next: SemanticsMatcher? = null,
) = clickWhenSettled(logTag, button, hasTestTag(tag), timeoutMs, next)

/** [clickWhenSettled] for the single node matching [target]. */
fun AndroidComposeTestRule<*, out ComponentActivity>.clickWhenSettled(
    logTag: String,
    button: String,
    target: SemanticsMatcher,
    timeoutMs: Long,
    next: SemanticsMatcher? = null,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        val window = activity.window
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }
    waitForIdle()
    val scrollable = generateSequence(onNode(target).fetchSemanticsNode().parent, SemanticsNode::parent)
        .any { SemanticsActions.ScrollBy in it.config }
    if (scrollable) onNode(target).performScrollTo()
    awaitSettled(logTag, button, target, timeoutMs)
    Log.i(logTag, "clicking $button at ${onNode(target).fetchSemanticsNode().boundsInWindow}")
    onNode(target).assertIsDisplayed().assertIsEnabled().performClick()
    if (next == null) return
    val reached = try {
        waitUntil(RETRY_AFTER_MS) { onAllNodes(next).fetchSemanticsNodes().isNotEmpty() }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }
    if (reached || onAllNodes(target and isEnabled()).fetchSemanticsNodes().isEmpty()) return
    Log.w(logTag, "retrying click on $button: still enabled and the next step did not appear within $RETRY_AFTER_MS ms")
    awaitSettled(logTag, button, target, timeoutMs)
    onNode(target).assertIsDisplayed().assertIsEnabled().performClick()
}

/** Waits up to [timeoutMs] for a hidden IME and [SETTLE_MS] of unchanged bounds; logs and continues otherwise. */
private fun AndroidComposeTestRule<*, out ComponentActivity>.awaitSettled(
    logTag: String,
    button: String,
    target: SemanticsMatcher,
    timeoutMs: Long,
) {
    var last: List<Rect>? = null
    var since = 0L
    try {
        waitUntil(timeoutMs) {
            val bounds = generateSequence(onNode(target).fetchSemanticsNode(), SemanticsNode::parent)
                .map { it.boundsInWindow }.toList()
            val now = SystemClock.uptimeMillis()
            if (imeVisible() || bounds != last) {
                last = bounds
                since = now
                false
            } else {
                now - since >= SETTLE_MS
            }
        }
    } catch (_: ComposeTimeoutException) {
        Log.w(logTag, "layout around $button not settled after $timeoutMs ms (IME visible: ${imeVisible()}); clicking anyway")
    }
}

private fun AndroidComposeTestRule<*, out ComponentActivity>.imeVisible(): Boolean {
    var visible = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        visible = insets != null &&
            (insets.isVisible(WindowInsetsCompat.Type.ime()) || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0)
    }
    return visible
}

private const val SETTLE_MS = 300L
private const val RETRY_AFTER_MS = 3_000L
