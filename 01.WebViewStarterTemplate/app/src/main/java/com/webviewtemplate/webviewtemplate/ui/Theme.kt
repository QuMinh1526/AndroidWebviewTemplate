package com.webviewtemplate.webviewtemplate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object StudioColors {
    val Background = Color(0xFF050508)
    val Surface = Color(0xFF0E0E14)
    val Card = Color(0xFF15151E)
    val CardElevated = Color(0xFF1C1C2A)
    val Accent = Color(0xFF7C5CFC)
    val AccentDim = Color(0xFF4A3598)
    val Secondary = Color(0xFFFC5CF8)
    val MeterGreen = Color(0xFF3DFCAC)
    val MeterAmber = Color(0xFFFCB43D)
    val MeterRed = Color(0xFFFC3D5C)
    val GainReduction = Color(0xFFFC8C3D)
    val TextPrimary = Color(0xFFE8E8F0)
    val TextMuted = Color(0xFF666680)
    val Border = Color(0xFF252535)
}

private val studioScheme = darkColorScheme(
    primary = StudioColors.Accent,
    secondary = StudioColors.Secondary,
    background = StudioColors.Background,
    surface = StudioColors.Surface,
    surfaceVariant = StudioColors.Card,
    onBackground = StudioColors.TextPrimary,
    onSurface = StudioColors.TextPrimary,
    onSurfaceVariant = StudioColors.TextMuted,
    outline = StudioColors.Border,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = studioScheme, content = content)
}
