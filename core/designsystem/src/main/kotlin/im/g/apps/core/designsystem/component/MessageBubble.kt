package im.g.apps.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.theme.GimTheme

private val BubbleRadius = 18.dp
private val BubbleGroupedRadius = 5.dp
private val BubbleTailWidth = 7.dp
private val BubbleTailHeight = 12.dp

/**
 * Chat bubble. Consecutive bubbles from the same sender form a run: the corners facing a
 * neighbour in the run are tightened, and only the last bubble of the run carries a tail.
 * Every bubble reserves the tail gutter so bubbles in a run stay aligned.
 */
@Composable
fun MessageBubble(
    outgoing: Boolean,
    groupedWithPrevious: Boolean,
    groupedWithNext: Boolean,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = GimTheme.colors
    val shape = BubbleShape(
        outgoing = outgoing,
        groupedWithPrevious = groupedWithPrevious,
        groupedWithNext = groupedWithNext,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (groupedWithPrevious) 2.dp else 8.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        if (outgoing) Spacer(Modifier.width(56.dp))
        Surface(
            modifier = Modifier.weight(1f, fill = false),
            shape = shape,
            color = if (outgoing) colors.bubbleOutgoing else colors.bubbleIncoming,
            contentColor = if (outgoing) colors.onBubbleOutgoing else colors.onBubbleIncoming,
            shadowElevation = if (outgoing) 0.dp else 0.5.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 12.dp + if (outgoing) 0.dp else BubbleTailWidth,
                    end = 12.dp + if (outgoing) BubbleTailWidth else 0.dp,
                    top = 7.dp,
                    bottom = 6.dp,
                ),
            ) {
                content()
                Box(modifier = Modifier.align(Alignment.End).padding(top = 2.dp)) {
                    CompositionLocalProvider(LocalContentColor provides colors.bubbleMeta) {
                        footer()
                    }
                }
            }
        }
        if (!outgoing) Spacer(Modifier.width(56.dp))
    }
}

/** Rounded bubble outline drawn in a box that includes the tail gutter on the sender side. */
internal class BubbleShape(
    private val outgoing: Boolean,
    private val groupedWithPrevious: Boolean,
    private val groupedWithNext: Boolean,
    private val radius: Dp = BubbleRadius,
    private val groupedRadius: Dp = BubbleGroupedRadius,
    private val tailWidth: Dp = BubbleTailWidth,
    private val tailHeight: Dp = BubbleTailHeight,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val big = with(density) { radius.toPx() }.coerceAtMost(h / 2)
        val small = with(density) { groupedRadius.toPx() }.coerceAtMost(h / 2)
        val t = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }.coerceAtMost(h)
        val tail = !groupedWithNext

        // Geometry is built for a bubble whose tail sits on the leading side; the sender side
        // follows outgoing/incoming and the layout direction.
        val tailOnLeft = (!outgoing) xor (layoutDirection == LayoutDirection.Rtl)
        fun x(value: Float) = if (tailOnLeft) value else w - value

        val senderTop = if (groupedWithPrevious) small else big
        val senderBottom = if (groupedWithNext) small else big
        val path = Path().apply {
            moveTo(x(t + senderTop), 0f)
            lineTo(x(w - big), 0f)
            quadraticTo(x(w), 0f, x(w), big)
            lineTo(x(w), h - big)
            quadraticTo(x(w), h, x(w - big), h)
            if (tail) {
                lineTo(x(0f), h)
                quadraticTo(x(t * 0.75f), h - th * 0.2f, x(t), h - th)
            } else {
                lineTo(x(t + senderBottom), h)
                quadraticTo(x(t), h, x(t), h - senderBottom)
            }
            lineTo(x(t), senderTop)
            quadraticTo(x(t), 0f, x(t + senderTop), 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

@GimPreviews
@Composable
private fun MessageBubblePreview() {
    GimTheme {
        Column(modifier = Modifier.padding(8.dp)) {
            val footer: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("12:04", style = MaterialTheme.typography.labelSmall)
                    StatusTicks(state = TickState.READ, modifier = Modifier.padding(start = 2.dp))
                }
            }
            MessageBubble(outgoing = false, groupedWithPrevious = false, groupedWithNext = true, footer = {}) {
                Text("Leaving Split at nine tomorrow")
            }
            MessageBubble(outgoing = false, groupedWithPrevious = true, groupedWithNext = false, footer = {}) {
                Text("Wind looks good for Hvar")
            }
            MessageBubble(outgoing = true, groupedWithPrevious = false, groupedWithNext = true, footer = {}) {
                Text("Perfect")
            }
            MessageBubble(outgoing = true, groupedWithPrevious = true, groupedWithNext = false, footer = footer) {
                Text("I'll bring the charts for the channel crossing")
            }
        }
    }
}
