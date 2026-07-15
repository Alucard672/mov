package com.gofilm.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0E12)
val BgElevated = Color(0xFF16161C)
val BgCard = Color(0xFF1C1C24)
val BgChip = Color(0xFF252532)
val TextPrimary = Color(0xFFF2F2F5)
val TextSecondary = Color(0xFF9A9AA8)
val TextMuted = Color(0xFF6B6B7A)
val Accent = Color(0xFFFFB020)
val AccentSoft = Color(0x24FFB020)
val Primary = Color(0xFF7C5CFF)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1000),
    secondary = Primary,
    background = Bg,
    surface = BgElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,
    outline = Color(0x14FFFFFF)
)

@Composable
fun GoFilmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
