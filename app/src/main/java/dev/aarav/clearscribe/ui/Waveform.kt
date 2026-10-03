package dev.aarav.clearscribe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Bar-style waveform. [peaks] are 0f..1f amplitude values, one per bar.
 * Bars before [progress] (0f..1f fraction of the clip) are drawn in the
 * accent color, the rest in a dim neutral — a simple played/unplayed split.
 * Tapping or dragging anywhere seeks via [onSeek] with a 0f..1f fraction.
 */
@Composable
fun Waveform(
    peaks: FloatArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = Color(0xFF7FE0C0),
    unplayedColor: Color = Color(0xFF4A4A4A),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        if (peaks.isEmpty()) return@Canvas
        val barCount = peaks.size
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val midY = size.height / 2f
        val progressX = size.width * progress.coerceIn(0f, 1f)

        peaks.forEachIndexed { i, peak ->
            val x = i * (barWidth + gap)
            val h = (peak.coerceIn(0.03f, 1f)) * size.height
            val color = if (x <= progressX) playedColor else unplayedColor
            drawLine(
                color = color,
                start = Offset(x + barWidth / 2f, midY - h / 2f),
                end = Offset(x + barWidth / 2f, midY + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
