package com.dama.tahany.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF0B6B4F)
private val GreenDark = Color(0xFF8FE3B0)
private val Canvas = Color(0xFFF3F6EF)
private val Ink = Color(0xFF17211C)
private val Coral = Color(0xFFB34A3C)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F0D8),
    onPrimaryContainer = Color(0xFF06251A),
    secondary = Color(0xFF4F6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8DA),
    onSecondaryContainer = Color(0xFF0D1F16),
    tertiary = Coral,
    onTertiary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Color(0xFFFCFDF8),
    surfaceContainer = Color(0xFFEBF1E7),
    surfaceContainerHigh = Color(0xFFE3EBDF),
    surfaceContainerHighest = Color(0xFFE1EBDF),
    outlineVariant = Color(0xFFC4CEC1),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE4E9E2),
    onSurfaceVariant = Color(0xFF434943),
    outline = Color(0xFF737A73),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFC6F0D8),
    secondary = Color(0xFFB7CCBE),
    onSecondary = Color(0xFF223529),
    secondaryContainer = Color(0xFF384B3F),
    onSecondaryContainer = Color(0xFFD3E8DA),
    tertiary = Color(0xFFFFB4A8),
    onTertiary = Color(0xFF690005),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE1E4DE),
    surface = Color(0xFF18211B),
    surfaceContainer = Color(0xFF202A22),
    surfaceContainerHigh = Color(0xFF29352C),
    surfaceContainerHighest = Color(0xFF29352C),
    outlineVariant = Color(0xFF414D43),
    onSurface = Color(0xFFE1E4DE),
    surfaceVariant = Color(0xFF434943),
    onSurfaceVariant = Color(0xFFC3C9C2),
    outline = Color(0xFF8D938C),
    error = Color(0xFFFFB4AB),
)

private val SystemTypography = Typography()
private val TahanyTypography = Typography(
    headlineLarge = SystemTypography.headlineLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = SystemTypography.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = SystemTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = SystemTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun TahanyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = TahanyTypography,
        content = content,
    )
}
