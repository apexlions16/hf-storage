package com.apexlions.hfstorage.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HfDarkColors = darkColorScheme(
    primary = Color(0xFFFFCF4A),
    onPrimary = Color(0xFF251A00),
    primaryContainer = Color(0xFF463600),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF002B3A),
    secondaryContainer = Color(0xFF06445B),
    onSecondaryContainer = Color(0xFFBCE9FF),
    tertiary = Color(0xFF79D6A7),
    background = Color(0xFF080D18),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF0E1626),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF172235),
    onSurfaceVariant = Color(0xFFB8C4D8),
    outline = Color(0xFF536178),
    error = Color(0xFFFFB4AB),
)

@Composable
fun HfStorageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HfDarkColors, content = content)
}
