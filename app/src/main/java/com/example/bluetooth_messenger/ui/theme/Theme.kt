package com.example.bluetooth_messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OffGridColors = darkColorScheme(
    primary = OnlineGreen,
    onPrimary = Color(0xFF06200F),
    secondary = Color(0xFF8EBBAA),
    background = AppBackground,
    surface = AppSurface,
    surfaceVariant = Color(0xFF20303D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9BAEBE),
    error = ErrorRed,
)

@Composable
fun BluetoothmessengerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OffGridColors, typography = Typography, content = content)
}
