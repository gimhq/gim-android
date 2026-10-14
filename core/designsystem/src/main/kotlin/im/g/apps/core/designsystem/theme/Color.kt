package im.g.apps.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette: deep indigo ink with a warm amber accent and a sea-glass secondary.
internal object GimPalette {
    val Indigo10 = Color(0xFF14104A)
    val Indigo20 = Color(0xFF261F77)
    val Indigo30 = Color(0xFF3A31A0)
    val Indigo40 = Color(0xFF4F45BE)
    val Indigo80 = Color(0xFFC6C0FF)
    val Indigo90 = Color(0xFFE4DFFF)
    val Indigo95 = Color(0xFFF2EFFF)

    val SeaGlass20 = Color(0xFF00382F)
    val SeaGlass30 = Color(0xFF005145)
    val SeaGlass40 = Color(0xFF006B5C)
    val SeaGlass60 = Color(0xFF2EA690)
    val SeaGlass80 = Color(0xFF7FD8C3)
    val SeaGlass90 = Color(0xFFB0F2DF)

    val Amber20 = Color(0xFF462A00)
    val Amber30 = Color(0xFF643F00)
    val Amber40 = Color(0xFF845400)
    val Amber80 = Color(0xFFFFB955)
    val Amber90 = Color(0xFFFFDDB4)

    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)

    val Neutral6 = Color(0xFF131318)
    val Neutral10 = Color(0xFF1C1B22)
    val Neutral12 = Color(0xFF201F26)
    val Neutral17 = Color(0xFF2A2931)
    val Neutral22 = Color(0xFF35343C)
    val Neutral90 = Color(0xFFE5E1EB)
    val Neutral94 = Color(0xFFF0ECF6)
    val Neutral96 = Color(0xFFF6F2FC)
    val Neutral98 = Color(0xFFFCF8FF)
    val NeutralVariant30 = Color(0xFF47464F)
    val NeutralVariant50 = Color(0xFF787680)
    val NeutralVariant60 = Color(0xFF928F9A)
    val NeutralVariant80 = Color(0xFFC8C5D0)
    val NeutralVariant90 = Color(0xFFE4E1EC)

    val AvatarColors = listOf(
        Color(0xFF5B4FCF),
        Color(0xFF00897B),
        Color(0xFFD4572A),
        Color(0xFF8E44AD),
        Color(0xFF2E7D32),
        Color(0xFFC2185B),
        Color(0xFF1565C0),
        Color(0xFFB7791F),
    )
}

internal val LightColorScheme = lightColorScheme(
    primary = GimPalette.Indigo40,
    onPrimary = Color.White,
    primaryContainer = GimPalette.Indigo90,
    onPrimaryContainer = GimPalette.Indigo10,
    secondary = GimPalette.SeaGlass40,
    onSecondary = Color.White,
    secondaryContainer = GimPalette.SeaGlass90,
    onSecondaryContainer = GimPalette.SeaGlass20,
    tertiary = GimPalette.Amber40,
    onTertiary = Color.White,
    tertiaryContainer = GimPalette.Amber90,
    onTertiaryContainer = GimPalette.Amber20,
    error = GimPalette.Red40,
    onError = Color.White,
    errorContainer = GimPalette.Red90,
    onErrorContainer = GimPalette.Red20,
    background = GimPalette.Neutral98,
    onBackground = GimPalette.Neutral10,
    surface = GimPalette.Neutral98,
    onSurface = GimPalette.Neutral10,
    surfaceVariant = GimPalette.NeutralVariant90,
    onSurfaceVariant = GimPalette.NeutralVariant30,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = GimPalette.Neutral96,
    surfaceContainer = GimPalette.Neutral94,
    outline = GimPalette.NeutralVariant50,
    outlineVariant = GimPalette.NeutralVariant80,
)

internal val DarkColorScheme = darkColorScheme(
    primary = GimPalette.Indigo80,
    onPrimary = GimPalette.Indigo20,
    primaryContainer = GimPalette.Indigo30,
    onPrimaryContainer = GimPalette.Indigo90,
    secondary = GimPalette.SeaGlass80,
    onSecondary = GimPalette.SeaGlass20,
    secondaryContainer = GimPalette.SeaGlass30,
    onSecondaryContainer = GimPalette.SeaGlass90,
    tertiary = GimPalette.Amber80,
    onTertiary = GimPalette.Amber20,
    tertiaryContainer = GimPalette.Amber30,
    onTertiaryContainer = GimPalette.Amber90,
    error = GimPalette.Red80,
    onError = GimPalette.Red20,
    errorContainer = GimPalette.Red30,
    onErrorContainer = GimPalette.Red90,
    background = GimPalette.Neutral6,
    onBackground = GimPalette.Neutral90,
    surface = GimPalette.Neutral6,
    onSurface = GimPalette.Neutral90,
    surfaceVariant = GimPalette.NeutralVariant30,
    onSurfaceVariant = GimPalette.NeutralVariant80,
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = GimPalette.Neutral10,
    surfaceContainer = GimPalette.Neutral12,
    outline = GimPalette.NeutralVariant60,
    outlineVariant = GimPalette.NeutralVariant30,
)
