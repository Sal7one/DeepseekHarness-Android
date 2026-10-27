package io.github.jackwener.dsh.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun style(size: Int, line: Int, weight: Int) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight))

private val DshTypography = Typography(
    headlineMedium = style(28, 36, 600),
    headlineSmall = style(24, 32, 600),
    titleLarge = style(20, 28, 500),
    titleMedium = style(16, 24, 500),
    titleSmall = style(14, 22, 500),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 22, 400),
    bodySmall = style(13, 20, 400),
    labelLarge = style(14, 22, 500),
    labelMedium = style(12, 18, 500),
    labelSmall = style(11, 14, 500),
)

private val DshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Card radius from DSH settings cards / bubbles. */
val CardShape = RoundedCornerShape(20.dp)

/** One physical pixel, the DSH hairline. */
val Hairline = 0.5.dp

@Composable
fun DshTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDshColors provides if (darkTheme) DarkDshColors else LightDshColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = DshTypography,
            shapes = DshShapes,
            content = content,
        )
    }
}

object DshTheme {
    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshColors.current
}
