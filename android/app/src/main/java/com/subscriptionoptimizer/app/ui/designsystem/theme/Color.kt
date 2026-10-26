package com.subscriptionoptimizer.app.ui.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Indigo / blue brand with a green reserved for savings. Text-on-surface pairs meet WCAG AA.

internal val LightColors = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0B2A6B),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF052E16),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EAF3),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4FA),
    surfaceContainer = Color(0xFFEEF0F7),
    surfaceContainerHigh = Color(0xFFE8EAF3),
    surfaceContainerHighest = Color(0xFFE2E5EF),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFE5E7EB),
    inverseSurface = Color(0xFF1F2333),
    inverseOnSurface = Color(0xFFF3F4F6),
    inversePrimary = Color(0xFFA5B4FC),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF0B2A6B),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFDCFCE7),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF7F1D1D),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0F1220),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF0F1220),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF222639),
    onSurfaceVariant = Color(0xFFA1A7B8),
    surfaceContainerLowest = Color(0xFF0A0D18),
    surfaceContainerLow = Color(0xFF151829),
    surfaceContainer = Color(0xFF1A1E30),
    surfaceContainerHigh = Color(0xFF222639),
    surfaceContainerHighest = Color(0xFF2B3045),
    outline = Color(0xFF7B8194),
    outlineVariant = Color(0xFF2B3045),
    inverseSurface = Color(0xFFE5E7EB),
    inverseOnSurface = Color(0xFF1F2333),
    inversePrimary = Color(0xFF4338CA),
)

/** Colors outside the Material scheme: savings, warnings and the hero gradient. */
data class ExtendedColors(
    val savings: Color,
    val onSavings: Color,
    val savingsContainer: Color,
    val onSavingsContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val onHero: Color,
)

internal val LightExtendedColors = ExtendedColors(
    savings = Color(0xFF15803D),
    onSavings = Color(0xFFFFFFFF),
    savingsContainer = Color(0xFFDCFCE7),
    onSavingsContainer = Color(0xFF052E16),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    heroGradientStart = Color(0xFF4338CA),
    heroGradientEnd = Color(0xFF2563EB),
    onHero = Color(0xFFFFFFFF),
)

internal val DarkExtendedColors = ExtendedColors(
    savings = Color(0xFF4ADE80),
    onSavings = Color(0xFF052E16),
    savingsContainer = Color(0xFF14532D),
    onSavingsContainer = Color(0xFFDCFCE7),
    warningContainer = Color(0xFF451A03),
    onWarningContainer = Color(0xFFFDE68A),
    heroGradientStart = Color(0xFF312E81),
    heroGradientEnd = Color(0xFF1E3A8A),
    onHero = Color(0xFFFFFFFF),
)
