package com.saeed.ikev2vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF315CE7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EDFF), onPrimaryContainer = Color(0xFF2344A0),
    secondary = Color(0xFF526184), secondaryContainer = Color(0xFFEDF1FC), onSecondaryContainer = Color(0xFF263450),
    background = Color(0xFFF5F7FB), onBackground = Color(0xFF17243B),
    surface = Color.White, onSurface = Color(0xFF17243B), onSurfaceVariant = Color(0xFF667187),
    surfaceContainer = Color(0xFFEEF1F7), surfaceContainerLow = Color(0xFFF9FAFD), surfaceContainerHigh = Color(0xFFE9EDF5),
    outline = Color(0xFF8994A7), outlineVariant = Color(0xFFE0E5EF), error = Color(0xFFB3261E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8BEFF), onPrimary = Color(0xFF102B79),
    primaryContainer = Color(0xFF24365D), onPrimaryContainer = Color(0xFFD4DFFF),
    secondary = Color(0xFFB7C5E5), secondaryContainer = Color(0xFF23304A), onSecondaryContainer = Color(0xFFD9E3FF),
    background = Color(0xFF101725), onBackground = Color(0xFFE5EBF7),
    surface = Color(0xFF192235), onSurface = Color(0xFFE5EBF7), onSurfaceVariant = Color(0xFFAAB6CC),
    surfaceContainer = Color(0xFF202C41), surfaceContainerLow = Color(0xFF151E2F), surfaceContainerHigh = Color(0xFF29354A),
    outline = Color(0xFF7C8BA6), outlineVariant = Color(0xFF344158), error = Color(0xFFFFB4AB),
)
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun Ikev2VpnTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes, content = content)
}
