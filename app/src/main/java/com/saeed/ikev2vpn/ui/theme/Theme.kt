package com.saeed.ikev2vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.AppTheme

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

private val TerminalLight = themeColors(
    dark = false,
    primary = Color(0xFF216C38), onPrimary = Color.White,
    primaryContainer = Color(0xFFD2EFD4), onPrimaryContainer = Color(0xFF123B20),
    background = Color(0xFFF1F7EE), surface = Color(0xFFF9FCF6),
    surfaceLow = Color(0xFFF5F9F1), surfaceContainer = Color(0xFFE8F0E4), surfaceHigh = Color(0xFFDFE9DA),
    onSurface = Color(0xFF172B1C), muted = Color(0xFF4F6654),
    outline = Color(0xFF738775), outlineVariant = Color(0xFFCAD9C8),
)
private val TerminalDark = themeColors(
    dark = true,
    primary = Color(0xFF8BDF95), onPrimary = Color(0xFF093916),
    primaryContainer = Color(0xFF183E24), onPrimaryContainer = Color(0xFFAFF3B5),
    background = Color(0xFF0C140E), surface = Color(0xFF131F16),
    surfaceLow = Color(0xFF101A12), surfaceContainer = Color(0xFF1B2A1E), surfaceHigh = Color(0xFF25372A),
    onSurface = Color(0xFFD7EBD8), muted = Color(0xFFA0B7A2),
    outline = Color(0xFF738C77), outlineVariant = Color(0xFF354C3A),
)
private val AmberLight = themeColors(
    dark = false,
    primary = Color(0xFF825400), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3AB), onPrimaryContainer = Color(0xFF553500),
    background = Color(0xFFFBF5E9), surface = Color(0xFFFFFBF2),
    surfaceLow = Color(0xFFFCF7ED), surfaceContainer = Color(0xFFF3EBDD), surfaceHigh = Color(0xFFEBE0CC),
    onSurface = Color(0xFF302719), muted = Color(0xFF726044),
    outline = Color(0xFF938267), outlineVariant = Color(0xFFE0D2BA),
)
private val AmberDark = themeColors(
    dark = true,
    primary = Color(0xFFF2C166), onPrimary = Color(0xFF432D00),
    primaryContainer = Color(0xFF4C3615), onPrimaryContainer = Color(0xFFFFDDA1),
    background = Color(0xFF18130B), surface = Color(0xFF241D12),
    surfaceLow = Color(0xFF1E180F), surfaceContainer = Color(0xFF302619), surfaceHigh = Color(0xFF3D3120),
    onSurface = Color(0xFFF2E3CA), muted = Color(0xFFC6B393),
    outline = Color(0xFF9C8864), outlineVariant = Color(0xFF53432B),
)
private val RoseLight = themeColors(
    dark = false,
    primary = Color(0xFF8B4668), onPrimary = Color.White,
    primaryContainer = Color(0xFFF7DCE8), onPrimaryContainer = Color(0xFF612B47),
    background = Color(0xFFFBF4F6), surface = Color(0xFFFFFAFC),
    surfaceLow = Color(0xFFFCF6F8), surfaceContainer = Color(0xFFF3E9EE), surfaceHigh = Color(0xFFECDDE5),
    onSurface = Color(0xFF34232F), muted = Color(0xFF705A68),
    outline = Color(0xFF9D8293), outlineVariant = Color(0xFFE2CED9),
)
private val RoseDark = themeColors(
    dark = true,
    primary = Color(0xFFE8ACCA), onPrimary = Color(0xFF4D1F37),
    primaryContainer = Color(0xFF573047), onPrimaryContainer = Color(0xFFFFD8EB),
    background = Color(0xFF1C131B), surface = Color(0xFF291F28),
    surfaceLow = Color(0xFF231922), surfaceContainer = Color(0xFF352834), surfaceHigh = Color(0xFF423440),
    onSurface = Color(0xFFF1DFEA), muted = Color(0xFFC7AFBF),
    outline = Color(0xFFA68A9D), outlineVariant = Color(0xFF574151),
)

// Keep surfaces, controls and inverse colors in the same palette; retain Material error colors.
private fun themeColors(
    dark: Boolean,
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    background: Color, surface: Color, surfaceLow: Color, surfaceContainer: Color, surfaceHigh: Color,
    onSurface: Color, muted: Color, outline: Color, outlineVariant: Color,
): ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = muted, onSecondary = background,
    secondaryContainer = primaryContainer, onSecondaryContainer = onPrimaryContainer,
    tertiary = primary, onTertiary = onPrimary,
    tertiaryContainer = primaryContainer, onTertiaryContainer = onPrimaryContainer,
    background = background, onBackground = onSurface,
    surface = surface, onSurface = onSurface, surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
    surfaceTint = primary, surfaceDim = if (dark) background else surfaceHigh,
    surfaceBright = if (dark) surfaceHigh else surface,
    surfaceContainerLowest = background, surfaceContainerLow = surfaceLow,
    surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
    outline = outline, outlineVariant = outlineVariant,
    inverseSurface = onSurface, inverseOnSurface = background, inversePrimary = primaryContainer,
)

internal fun AppTheme.colors(dark: Boolean): ColorScheme = when (this) {
    AppTheme.BLUE -> if (dark) DarkColors else LightColors
    AppTheme.TERMINAL -> if (dark) TerminalDark else TerminalLight
    AppTheme.AMBER -> if (dark) AmberDark else AmberLight
    AppTheme.ROSE -> if (dark) RoseDark else RoseLight
}

private val DefaultTypography = Typography()

// Change families only: keep the existing sizes, weights, line heights and spacing.
internal fun AppTheme.typography(): Typography {
    val headingFamily = when (this) {
        AppTheme.BLUE -> return DefaultTypography
        AppTheme.TERMINAL, AppTheme.AMBER -> FontFamily.Monospace
        AppTheme.ROSE -> FontFamily.Serif
    }
    val bodyFamily = if (this == AppTheme.ROSE) FontFamily.SansSerif else headingFamily
    return with(DefaultTypography) {
        Typography(
            displayLarge = displayLarge.copy(fontFamily = headingFamily),
            displayMedium = displayMedium.copy(fontFamily = headingFamily),
            displaySmall = displaySmall.copy(fontFamily = headingFamily),
            headlineLarge = headlineLarge.copy(fontFamily = headingFamily),
            headlineMedium = headlineMedium.copy(fontFamily = headingFamily),
            headlineSmall = headlineSmall.copy(fontFamily = headingFamily),
            titleLarge = titleLarge.copy(fontFamily = headingFamily),
            titleMedium = titleMedium.copy(fontFamily = headingFamily),
            titleSmall = titleSmall.copy(fontFamily = headingFamily),
            bodyLarge = bodyLarge.copy(fontFamily = bodyFamily),
            bodyMedium = bodyMedium.copy(fontFamily = bodyFamily),
            bodySmall = bodySmall.copy(fontFamily = bodyFamily),
            labelLarge = labelLarge.copy(fontFamily = bodyFamily),
            labelMedium = labelMedium.copy(fontFamily = bodyFamily),
            labelSmall = labelSmall.copy(fontFamily = bodyFamily),
        )
    }
}

@Composable
fun Ikev2VpnTheme(darkTheme: Boolean, theme: AppTheme = AppTheme.BLUE, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = theme.colors(darkTheme), typography = theme.typography(),
        shapes = AppShapes, content = content)
}
