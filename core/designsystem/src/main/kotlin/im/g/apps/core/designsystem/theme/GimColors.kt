package im.g.apps.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Chat-specific colors that have no slot in the Material 3 color scheme. */
@Immutable
data class GimColors(
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleMeta: Color,
    val tick: Color,
    val tickRead: Color,
    val tickFailed: Color,
    val unreadBadge: Color,
    val onUnreadBadge: Color,
    val unreadBadgeMuted: Color,
    val onUnreadBadgeMuted: Color,
    val dateChip: Color,
    val onDateChip: Color,
    val banner: Color,
    val onBanner: Color,
)

internal val LightGimColors = GimColors(
    bubbleIncoming = Color.White,
    onBubbleIncoming = GimPalette.Neutral10,
    bubbleOutgoing = GimPalette.Indigo90,
    onBubbleOutgoing = GimPalette.Indigo10,
    bubbleMeta = GimPalette.NeutralVariant50,
    tick = GimPalette.NeutralVariant50,
    tickRead = GimPalette.SeaGlass40,
    tickFailed = GimPalette.Red40,
    unreadBadge = GimPalette.Indigo40,
    onUnreadBadge = Color.White,
    unreadBadgeMuted = GimPalette.NeutralVariant80,
    onUnreadBadgeMuted = GimPalette.NeutralVariant30,
    dateChip = GimPalette.Indigo95,
    onDateChip = GimPalette.Indigo30,
    banner = GimPalette.Amber90,
    onBanner = GimPalette.Amber20,
)

internal val DarkGimColors = GimColors(
    bubbleIncoming = GimPalette.Neutral17,
    onBubbleIncoming = GimPalette.Neutral90,
    bubbleOutgoing = GimPalette.Indigo30,
    onBubbleOutgoing = GimPalette.Indigo95,
    bubbleMeta = GimPalette.NeutralVariant80,
    tick = GimPalette.NeutralVariant80,
    tickRead = GimPalette.SeaGlass80,
    tickFailed = GimPalette.Red80,
    unreadBadge = GimPalette.Indigo80,
    onUnreadBadge = GimPalette.Indigo20,
    unreadBadgeMuted = GimPalette.Neutral22,
    onUnreadBadgeMuted = GimPalette.NeutralVariant80,
    dateChip = GimPalette.Neutral22,
    onDateChip = GimPalette.Neutral90,
    banner = GimPalette.Amber30,
    onBanner = GimPalette.Amber90,
)

val LocalGimColors = staticCompositionLocalOf { LightGimColors }
