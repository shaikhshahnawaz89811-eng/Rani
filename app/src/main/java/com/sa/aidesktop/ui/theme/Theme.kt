package com.sa.aidesktop.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF9B5CFF), secondary = Color(0xFF4DB8FF), background = Color(0xFF050611), surface = Color(0xFF0B0C15), surfaceVariant = Color(0xFF151526), onSurface = Color(0xFFE8E7F0), onBackground = Color.White
)
@Composable fun SADesktopTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = Dark, typography = Typography(), content = content) }
