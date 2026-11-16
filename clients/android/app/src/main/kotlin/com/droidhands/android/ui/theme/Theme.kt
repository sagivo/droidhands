package com.droidhands.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DroidHands's design system. One brand palette (no wallpaper-based dynamic color, so every phone sees
 * the same, tested colors), inset grouped surfaces on a tinted background, pill buttons. Usage rules
 * live in docs/DESIGN.md; components in ui/components use these tokens and screens use the components.
 */
@Composable
fun DroidHandsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Raw palette. Screens never use these directly: use MaterialTheme.colorScheme or statusColors(). */
private object Palette {
    val Indigo600 = Color(0xFF4F46E5)
    val Indigo500 = Color(0xFF6366F1)
    val Indigo400 = Color(0xFF818CF8)
    val Indigo300 = Color(0xFFA5B4FC)
    val Indigo100 = Color(0xFFE0E7FF)
    val Indigo50 = Color(0xFFEEF0FF)
    val Indigo950 = Color(0xFF1E1B4B)
    val Indigo900 = Color(0xFF2B2870)

    val Red600 = Color(0xFFDC2626)
    val Red400 = Color(0xFFF87171)
    val Red50 = Color(0xFFFEF2F2)
    val Red950 = Color(0xFF3B0D0D)

    val Ink = Color(0xFF0F1117)
    val Gray700 = Color(0xFF4B5060)
    val Gray500 = Color(0xFF6B7080)
    val Gray300 = Color(0xFFD5D7DE)
    val Gray200 = Color(0xFFE6E7EC)
    val Gray100 = Color(0xFFF0F1F4)
    val Gray50 = Color(0xFFF5F6F8)

    val Night = Color(0xFF0B0C10)
    val Night1 = Color(0xFF16171D)
    val Night2 = Color(0xFF1E2028)
    val Night3 = Color(0xFF272A33)
    val Night4 = Color(0xFF30333D)
    val NightText = Color(0xFFF2F3F6)
    val NightMuted = Color(0xFFA2A6B3)
    val NightOutline = Color(0xFF3A3D48)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = Palette.Indigo600,
    onPrimary = Color.White,
    primaryContainer = Palette.Indigo50,
    onPrimaryContainer = Palette.Indigo950,
    inversePrimary = Palette.Indigo300,
    secondary = Palette.Gray700,
    onSecondary = Color.White,
    secondaryContainer = Palette.Indigo100,
    onSecondaryContainer = Palette.Indigo950,
    tertiary = Palette.Indigo500,
    onTertiary = Color.White,
    tertiaryContainer = Palette.Indigo50,
    onTertiaryContainer = Palette.Indigo950,
    background = Palette.Gray50,
    onBackground = Palette.Ink,
    surface = Palette.Gray50,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Gray100,
    onSurfaceVariant = Palette.Gray500,
    surfaceTint = Palette.Indigo600,
    inverseSurface = Palette.Ink,
    inverseOnSurface = Color.White,
    error = Palette.Red600,
    onError = Color.White,
    errorContainer = Palette.Red50,
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Palette.Gray300,
    outlineVariant = Palette.Gray200,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Palette.Gray100,
    // Cards and grouped lists sit on surfaceContainerLowest (white) over the tinted background.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    // Dialogs and menus (surfaceContainerHigh) stay white; fills such as code blocks use Highest.
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Palette.Gray100,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.Indigo400,
    onPrimary = Palette.Indigo950,
    primaryContainer = Palette.Indigo900,
    onPrimaryContainer = Palette.Indigo100,
    inversePrimary = Palette.Indigo600,
    secondary = Palette.NightMuted,
    onSecondary = Palette.Night,
    secondaryContainer = Palette.Indigo900,
    onSecondaryContainer = Palette.Indigo100,
    tertiary = Palette.Indigo300,
    onTertiary = Palette.Indigo950,
    tertiaryContainer = Palette.Indigo900,
    onTertiaryContainer = Palette.Indigo100,
    background = Palette.Night,
    onBackground = Palette.NightText,
    surface = Palette.Night,
    onSurface = Palette.NightText,
    surfaceVariant = Palette.Night2,
    onSurfaceVariant = Palette.NightMuted,
    surfaceTint = Palette.Indigo400,
    inverseSurface = Palette.NightText,
    inverseOnSurface = Palette.Night,
    error = Palette.Red400,
    onError = Palette.Red950,
    errorContainer = Palette.Red950,
    onErrorContainer = Color(0xFFFECACA),
    outline = Palette.NightOutline,
    outlineVariant = Palette.Night3,
    scrim = Color.Black,
    surfaceBright = Palette.Night3,
    surfaceDim = Palette.Night,
    surfaceContainerLowest = Palette.Night1,
    surfaceContainerLow = Palette.Night1,
    surfaceContainer = Palette.Night1,
    surfaceContainerHigh = Palette.Night2,
    surfaceContainerHighest = Palette.Night4,
)

private val AppTypography = Typography().let { base ->
    fun TextStyle.tuned(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    Typography(
        displaySmall = base.displaySmall.tuned(34, 40, FontWeight.Bold, -0.5),
        headlineLarge = base.headlineLarge.tuned(30, 36, FontWeight.Bold, -0.4),
        headlineMedium = base.headlineMedium.tuned(26, 32, FontWeight.Bold, -0.3),
        headlineSmall = base.headlineSmall.tuned(22, 28, FontWeight.SemiBold, -0.2),
        titleLarge = base.titleLarge.tuned(20, 26, FontWeight.SemiBold, -0.1),
        titleMedium = base.titleMedium.tuned(16, 22, FontWeight.SemiBold),
        titleSmall = base.titleSmall.tuned(14, 20, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.tuned(16, 23, FontWeight.Normal),
        bodyMedium = base.bodyMedium.tuned(14, 20, FontWeight.Normal),
        bodySmall = base.bodySmall.tuned(12, 17, FontWeight.Normal, 0.1),
        labelLarge = base.labelLarge.tuned(15, 20, FontWeight.SemiBold),
        labelMedium = base.labelMedium.tuned(13, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.tuned(11, 14, FontWeight.SemiBold, 0.3),
    )
}

/** Corner radii: 8 (chips, code), 12 (fields, notices), 16 (cards, list groups), 24 (dialogs, sheets). */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
