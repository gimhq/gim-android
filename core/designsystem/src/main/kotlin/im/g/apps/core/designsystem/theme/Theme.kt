package im.g.apps.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun GimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGimColors provides if (darkTheme) DarkGimColors else LightGimColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = GimTypography,
            shapes = GimShapes,
            content = content,
        )
    }
}

object GimTheme {
    val colors: GimColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGimColors.current
}
