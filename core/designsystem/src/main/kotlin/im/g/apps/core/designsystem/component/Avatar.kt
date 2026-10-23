package im.g.apps.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import im.g.apps.core.designsystem.theme.GimPalette
import im.g.apps.core.designsystem.theme.GimTheme
import java.io.File

/**
 * Circular avatar. Draws the image at [imagePath] (a local file) when present; the initials on a
 * [colorKey]-derived color stay underneath, so they remain visible while loading or if decoding fails.
 * Pass the chat id as [colorKey] to keep the color stable when the name changes. With a
 * [contentDescription] the avatar is one semantics node; without it, it is decorative.
 */
@Composable
fun Avatar(
    name: String,
    imagePath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    colorKey: String = name,
    contentDescription: String? = null,
) {
    val initials = remember(name) { avatarInitials(name) }
    val background = remember(colorKey) { avatarColor(colorKey) }
    val fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initials, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (imagePath != null) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Up to two initials: first letters of the first and last words, or "?" for a blank name. */
internal fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val picked = if (words.size == 1) listOf(words[0]) else listOf(words.first(), words.last())
    return picked.joinToString("") { word ->
        val end = word.offsetByCodePoints(0, 1)
        word.substring(0, end).uppercase()
    }
}

/** Stable across processes and devices: String.hashCode is fixed by the JVM spec. */
internal fun avatarColor(key: String): Color {
    val palette = GimPalette.AvatarColors
    return palette[Math.floorMod(key.trim().lowercase().hashCode(), palette.size)]
}

@GimPreviews
@Composable
private fun AvatarPreview() {
    GimTheme {
        Row {
            Avatar(name = "Ada Lovelace", imagePath = null, size = 48.dp)
            Avatar(name = "Grace", imagePath = null, size = 48.dp)
            Avatar(name = "", imagePath = null, size = 32.dp)
        }
    }
}
