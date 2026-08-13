package com.mtzallqmy.aiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF33E8C6),
    onPrimary = Color(0xFF04251C),
    secondary = Color(0xFFA8C7FA),
    background = Color(0xFF101216),
    surface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFF2A2E37),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1DB39A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF445599),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F7),
)

@Composable
fun AegisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content,
    )
}
