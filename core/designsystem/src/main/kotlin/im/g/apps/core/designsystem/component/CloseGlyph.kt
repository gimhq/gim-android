package im.g.apps.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Decorative "x" mark; callers provide the semantics. */
@Composable
internal fun CloseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = size.minDimension * 0.14f
        drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}
