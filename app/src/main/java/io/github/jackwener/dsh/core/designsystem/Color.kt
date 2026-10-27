package io.github.jackwener.dsh.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Values ported from DeepSeek Harness web ui-theme (see docs/design-tokens.md §1, §8).

internal val LightColors = lightColorScheme(
    primary = Color(0xFF0F1115),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBEEF2),
    onPrimaryContainer = Color(0xFF0F1115),
    secondary = Color(0xFF4176E6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EDFD),
    onSecondaryContainer = Color(0xFF0E3074),
    tertiary = Color(0xFF4176E6),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F1115),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1115),
    onSurfaceVariant = Color(0xFF61666B),
    surfaceDim = Color(0xFFF9FAFB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF5F6F7),
    surfaceVariant = Color(0xFFF1F3F5),
    inverseSurface = Color(0xFF353638),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF7AAAFF),
    outline = Color(0x29000000),
    outlineVariant = Color(0x1A000000),
    error = Color(0xFFEC1313),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color(0x3D000000),
    surfaceTint = Color.Transparent,
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFF9FAFB),
    onPrimary = Color(0xFF0F1115),
    primaryContainer = Color(0xFF43454A),
    onPrimaryContainer = Color(0xFFF9FAFB),
    secondary = Color(0xFF7AAAFF),
    onSecondary = Color(0xFF0F1115),
    secondaryContainer = Color(0xFF34415B),
    onSecondaryContainer = Color(0xFFF9FAFB),
    tertiary = Color(0xFF5686FE),
    background = Color(0xFF151517),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF151517),
    onSurface = Color(0xFFF9FAFB),
    onSurfaceVariant = Color(0xFFCFD3D6),
    surfaceDim = Color(0xFF1B1B1C),
    surfaceContainerLowest = Color(0xFF151517),
    surfaceContainerLow = Color(0xFF232324),
    surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF353638),
    surfaceContainerHighest = Color(0xFF353638),
    surfaceVariant = Color(0xFF353638),
    inverseSurface = Color(0xFF43454A),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF4176E6),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFF25A5A),
    onError = Color(0xFF0F1115),
    errorContainer = Color(0xFF570C0C),
    onErrorContainer = Color(0xFFFEE2E2),
    scrim = Color(0x80000000),
    surfaceTint = Color.Transparent,
)

/** Colors DSH uses that Material 3 has no role for. */
@Immutable
data class DshColors(
    val labelTertiary: Color,
    val link: Color,
    val success: Color,
    val successContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val bubble: Color,
    val codeBlock: Color,
)

internal val LightDshColors = DshColors(
    labelTertiary = Color(0xFF8F9399),
    link = Color(0xFF4176E6),
    success = Color(0xFF22C55E),
    successContainer = Color(0xFFE6FAED),
    warn = Color(0xFFF59E0B),
    warnContainer = Color(0xFFFEF3C7),
    bubble = Color(0xFFF1F3F5),
    codeBlock = Color(0xFFF5F6F7),
)

internal val DarkDshColors = DshColors(
    labelTertiary = Color(0xFF8F9399),
    link = Color(0xFF7AAAFF),
    success = Color(0xFF22C55E),
    successContainer = Color(0xFF233C2C),
    warn = Color(0xFFFBBF24),
    warnContainer = Color(0xFF453610),
    bubble = Color(0xFF2C2C2E),
    codeBlock = Color(0xFF232324),
)

val LocalDshColors = staticCompositionLocalOf { LightDshColors }
