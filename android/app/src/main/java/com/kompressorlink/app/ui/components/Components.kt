package com.kompressorlink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.ui.theme.KlColors

// ── Pure logic (JVM-tested) ────────────────────────────────────────────

/** BandBar geometry (spec §8.2): the track spans [lo−w, hi+w] where
 *  w = band half-width — exactly the AMBER envelope. Returns the healthy
 *  zone's start/end and the clamped marker position as 0..1 fractions. */
data class BandBarFractions(val zoneStart: Float, val zoneEnd: Float, val marker: Float)

fun bandBarFractions(value: Float, lo: Float, hi: Float): BandBarFractions {
    // Degenerate band (lo == hi, or an inverted one) has zero track span;
    // dividing by it would silently yield NaN (NaN comparisons are always
    // false, so coerceIn never catches it). Fall back to a centered point.
    if (hi <= lo) return BandBarFractions(zoneStart = 0.5f, zoneEnd = 0.5f, marker = 0.5f)
    val w = (hi - lo) / 2f
    val trackLo = lo - w
    val trackHi = hi + w
    val span = trackHi - trackLo
    fun frac(x: Float) = ((x - trackLo) / span).coerceIn(0f, 1f)
    return BandBarFractions(zoneStart = frac(lo), zoneEnd = frac(hi), marker = frac(value))
}

/** Connection banner wording (spec §8.1). */
fun connectionBannerText(state: ConnectionState): String = when (state) {
    is ConnectionState.Simulated -> "Simulated · ${state.scenarioName}"
    is ConnectionState.Ready -> when {
        state.demo -> "Connected · demo device"
        state.klineConnected -> "Connected · K-line up"
        else -> "Connected · K-line down"
    }
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.NotAssociated -> "No device paired yet"
    ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
}

fun connectionBannerColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Ready -> KlColors.Ok
    is ConnectionState.Simulated -> KlColors.Neutral
    ConnectionState.Connecting -> KlColors.Watch
    else -> KlColors.Attention
}

// ── Composables (compile-verified; no Compose UI tests this phase) ─────

@Composable
fun ConnectionBanner(state: ConnectionState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(connectionBannerColor(state), CircleShape))
            Text(
                connectionBannerText(state),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun BandBar(value: Float, lo: Float, hi: Float, markerColor: Color) {
    val fractions = bandBarFractions(value, lo, hi)
    Box(
        Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier.fillMaxWidth(fractions.zoneEnd).height(6.dp)
                .background(Color.Transparent),
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxWidth((fractions.zoneEnd - fractions.zoneStart) / fractions.zoneEnd.coerceAtLeast(0.0001f))
                    .height(6.dp)
                    .background(KlColors.Ok.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
            )
        }
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(start = 0.dp)
                    .fillMaxWidth(fractions.marker.coerceAtLeast(0.02f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(10.dp).background(markerColor, CircleShape))
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun Sparkline(history: List<Float>) {
    if (history.size < 2) return
    val min = history.min()
    val max = history.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1f - (v - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))
    }
}
