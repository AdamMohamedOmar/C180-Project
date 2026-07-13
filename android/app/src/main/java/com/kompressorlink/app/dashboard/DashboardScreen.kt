package com.kompressorlink.app.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.uiState.collectAsState()
    val trims = state.gauges.filter { it.title == "STFT" || it.title == "LTFT" }
    val others = state.gauges.filterNot { it.title == "STFT" || it.title == "LTFT" }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (trims.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) { TrimsTile(trims) }
        }
        items(others) { gauge -> GaugeTile(gauge) }
    }
}

private fun levelColor(level: GaugeLevel): Color? = when (level) {
    GaugeLevel.AMBER -> Color(0xFFFFE0B2)
    GaugeLevel.RED -> Color(0xFFFFCDD2)
    else -> null
}

@Composable
private fun GaugeTile(gauge: GaugeUiState) {
    val tint = levelColor(gauge.level)
    Card(
        colors = tint?.let { CardDefaults.cardColors(containerColor = it) }
            ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(gauge.title, style = MaterialTheme.typography.labelMedium)
            if (gauge.level == GaugeLevel.UNAVAILABLE) {
                // The Untested contract on screen: no value, an honest label.
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Not provided by ECU", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${gauge.valueText} ${gauge.unit}",
                     style = MaterialTheme.typography.headlineMedium)
                gauge.bandText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                gauge.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
                Sparkline(gauge.history)
            }
        }
    }
}

// Severity ranking for picking the "worst" trim to tint, independent of
// GaugeLevel's declaration order (which places UNAVAILABLE last/highest for
// unrelated reasons). A genuine RED reading must always outrank a sibling
// that's merely UNAVAILABLE — a dropped poll must never mask a real fault.
private fun severityRank(level: GaugeLevel): Int = when (level) {
    GaugeLevel.RED -> 3
    GaugeLevel.AMBER -> 2
    GaugeLevel.OK -> 1
    GaugeLevel.NEUTRAL, GaugeLevel.UNAVAILABLE -> 0
}

@Composable
private fun TrimsTile(trims: List<GaugeUiState>) {
    // One card, two columns — spec §4.5's "one trims tile".
    val worst = trims.maxByOrNull { severityRank(it.level) }
    Card(
        colors = worst?.let { levelColor(it.level) }
            ?.let { CardDefaults.cardColors(containerColor = it) }
            ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Fuel trims", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                trims.forEach { g ->
                    Column {
                        Text(g.title, style = MaterialTheme.typography.labelSmall)
                        Text(if (g.level == GaugeLevel.UNAVAILABLE) "—" else "${g.valueText} ${g.unit}",
                             style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            trims.firstNotNullOfOrNull { it.hint }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Sparkline(history: List<Float>) {
    if (history.size < 2) return
    val min = history.min()
    val max = history.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp)) {
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
