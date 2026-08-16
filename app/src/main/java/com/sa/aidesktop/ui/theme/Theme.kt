package com.sa.aidesktop.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Sara's design tokens — sourced from the "RANI AI ASSISTANT – SARA" reference design
 * (docs/reference-image.png, "MODERN COLOR PALETTE" swatch). Kept as one real, named object so
 * every screen references the same seven colors instead of re-declaring its own hex literal
 * (Rule 21 Part B: no duplicate/ad-hoc constants). Nothing here is a placeholder — every value is
 * used by real, wired-up UI (chat header, workflow-step icons, status text, buttons).
 */
object SaraPalette {
    val Primary = Color(0xFF8B5CF6)
    val Secondary = Color(0xFF06D6A0)
    val Accent = Color(0xFF00BFFF)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val Surface = Color(0xFF1E2938)
    val Surface2 = Color(0xFF0F172A)
    val TextPrimary = Color(0xFFE2E8F0)
}

private val Dark = darkColorScheme(
    primary = SaraPalette.Primary,
    secondary = SaraPalette.Secondary,
    tertiary = SaraPalette.Accent,
    background = SaraPalette.Surface2,
    surface = SaraPalette.Surface2,
    surfaceVariant = SaraPalette.Surface,
    error = SaraPalette.Error,
    onSurface = SaraPalette.TextPrimary,
    onBackground = SaraPalette.TextPrimary
)
@Composable fun SADesktopTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = Dark, typography = Typography(), content = content) }
