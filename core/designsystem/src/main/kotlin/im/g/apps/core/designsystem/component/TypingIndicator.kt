package im.g.apps.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.theme.GimTheme

private const val DOT_COUNT = 3
private const val DOT_CYCLE_MS = 1200
private const val DOT_STAGGER_MS = 160

/**
 * "Alice is typing" row with pulsing dots. When [animate] is false (by default: the system
 * animator duration scale is 0, i.e. animations / reduce-motion are off) the dots are static.
 */
@Composable
fun TypingIndicator(
    label: String,
    modifier: Modifier = Modifier,
    animate: Boolean = !rememberAnimationsDisabled(),
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (animate) {
                val transition = rememberInfiniteTransition(label = "typing")
                repeat(DOT_COUNT) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = dotSpec(index),
                        label = "dot$index",
                    )
                    Dot(Modifier.alpha(alpha))
                }
            } else {
                repeat(DOT_COUNT) { Dot(Modifier.alpha(0.6f)) }
            }
        }
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
    }
}

private fun dotSpec(index: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = DOT_CYCLE_MS / 2, easing = LinearEasing),
    repeatMode = RepeatMode.Reverse,
    initialStartOffset = StartOffset(index * DOT_STAGGER_MS),
)

@Composable
private fun Dot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun rememberAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@GimPreviews
@Composable
private fun TypingIndicatorPreview() {
    GimTheme {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TypingIndicator(label = "Marta is typing", animate = true)
            TypingIndicator(label = "Marta is typing", animate = false)
        }
    }
}
