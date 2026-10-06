package dev.aarav.clearscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/** One UI Watch-ish: flat black, Samsung's teal-green accent (same as our icon). */
val GalaxyOneUiColors = Colors(
    primary = Color(0xFF7FE0C0),
    primaryVariant = Color(0xFF4FB89A),
    secondary = Color(0xFF3A3A3C),
    secondaryVariant = Color(0xFF2C2C2E),
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    error = Color(0xFFFF6B6B),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    onError = Color.Black,
)

/** watchOS-inspired: deep navy backdrop, vivid cyan accent — paired with GlassChip below for the translucent look. */
val LiquidGlassColors = Colors(
    primary = Color(0xFF64D2FF),
    primaryVariant = Color(0xFF0A84FF),
    secondary = Color(0xFFBF5AF2),
    secondaryVariant = Color(0xFF8E44D8),
    background = Color(0xFF0A0E27),
    surface = Color(0xFF1C2240),
    error = Color(0xFFFF6B6B),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFA0A8C8),
    onError = Color.Black,
)

fun colorsFor(theme: AppTheme): Colors = when (theme) {
    AppTheme.GALAXY_ONE_UI -> GalaxyOneUiColors
    AppTheme.LIQUID_GLASS -> LiquidGlassColors
}

/**
 * A translucent, pill-shaped chip for the Liquid Glass theme — layered
 * alpha + a soft gradient + a light border, standing in for real
 * backdrop-blur (not available at our minSdk on Wear Compose). Same shape
 * as androidx.wear.compose.material.Chip (icon/label/secondaryLabel, full
 * width, tap target) so it drops in wherever Chip is used.
 */
@Composable
fun GlassChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    secondaryLabel: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colors.primary,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .background(
                brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.28f * alpha), accent.copy(alpha = 0.10f * alpha)),
                ),
                shape = RoundedCornerShape(50),
            )
            .border(1.dp, accent.copy(alpha = 0.45f * alpha), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        androidx.compose.foundation.layout.Column {
            label()
            secondaryLabel?.invoke()
        }
    }
}
