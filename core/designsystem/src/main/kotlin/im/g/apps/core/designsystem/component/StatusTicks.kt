package im.g.apps.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.R
import im.g.apps.core.designsystem.theme.GimTheme

enum class TickState { PENDING, SENT, DELIVERED, READ, FAILED }

/**
 * Delivery state glyph: clock / one check / two checks / two accent checks / error mark. Decorative: the
 * message row announces [tickLabel] as part of its description.
 */
@Composable
fun StatusTicks(
    state: TickState,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val colors = GimTheme.colors
    val color = when (state) {
        TickState.READ -> colors.tickRead
        TickState.FAILED -> colors.tickFailed
        else -> colors.tick
    }
    Canvas(modifier = modifier.size(size)) {
        when (state) {
            TickState.PENDING -> drawClock(color)
            TickState.SENT -> drawCheck(color, offsetFraction = 0.1f)
            TickState.DELIVERED, TickState.READ -> {
                drawCheck(color, offsetFraction = -0.04f)
                drawCheck(color, offsetFraction = 0.22f)
            }
            TickState.FAILED -> drawError(color)
        }
    }
}

/** Spoken delivery state, e.g. "Read". */
@Composable
fun tickLabel(state: TickState): String = stringResource(
    when (state) {
        TickState.PENDING -> R.string.gim_tick_pending
        TickState.SENT -> R.string.gim_tick_sent
        TickState.DELIVERED -> R.string.gim_tick_delivered
        TickState.READ -> R.string.gim_tick_read
        TickState.FAILED -> R.string.gim_tick_failed
    },
)

private fun DrawScope.strokeOf() = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawCheck(color: Color, offsetFraction: Float) {
    val w = size.width
    val h = size.height
    val dx = w * offsetFraction
    val path = Path().apply {
        moveTo(w * 0.12f + dx, h * 0.52f)
        lineTo(w * 0.34f + dx, h * 0.74f)
        lineTo(w * 0.74f + dx, h * 0.28f)
    }
    drawPath(path, color, style = strokeOf())
}

private fun DrawScope.drawClock(color: Color) {
    val stroke = strokeOf()
    val radius = size.minDimension * 0.38f
    drawCircle(color, radius = radius, style = stroke)
    drawLine(color, center, Offset(center.x, center.y - radius * 0.6f), stroke.width, StrokeCap.Round)
    drawLine(color, center, Offset(center.x + radius * 0.45f, center.y), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawError(color: Color) {
    val radius = size.minDimension * 0.42f
    drawCircle(color, radius = radius)
    val bar = size.minDimension * 0.12f
    drawLine(
        Color.White,
        Offset(center.x, center.y - radius * 0.5f),
        Offset(center.x, center.y + radius * 0.1f),
        bar,
        StrokeCap.Round,
    )
    drawCircle(Color.White, radius = bar * 0.6f, center = Offset(center.x, center.y + radius * 0.5f))
}

@GimPreviews
@Composable
private fun StatusTicksPreview() {
    GimTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            TickState.entries.forEach { StatusTicks(state = it, size = 24.dp) }
        }
    }
}
