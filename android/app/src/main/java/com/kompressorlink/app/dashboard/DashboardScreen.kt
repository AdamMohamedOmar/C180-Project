package com.kompressorlink.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.components.BandBar
import com.kompressorlink.app.ui.components.Sparkline
import com.kompressorlink.app.ui.components.StatusChip
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.uiState.collectAsState()
    val trims = state.gauges.filter { it.signal.name == "STFT1" || it.signal.name == "LTFT1" }
    val others = state.gauges.filterNot { it.signal.name == "STFT1" || it.signal.name == "LTFT1" }

    Column(Modifier.fillMaxSize()) {
        if (state.warmIdle) {
            Row(Modifier.padding(start = 8.dp, top = 6.dp)) {
                StatusChip("Warm idle detected", KlColors.Neutral)
            }
        }
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
}

@Composable
private fun GaugeValue(gauge: GaugeUiState) {
    val valueColor = when (gauge.level) {
        GaugeLevel.AMBER -> KlColors.Watch
        GaugeLevel.RED -> KlColors.Attention
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text("${gauge.valueText} ${gauge.unit}",
         style = MaterialTheme.typography.headlineMedium, color = valueColor)
}

@Composable
private fun GaugeTile(gauge: GaugeUiState) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(gauge.title, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (gauge.level == GaugeLevel.UNAVAILABLE) {
                // The Untested contract on screen: no value, an honest label.
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Not provided by ECU", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                GaugeValue(gauge)
                gauge.band?.let { band ->
                    BandBar(
                        value = gauge.valueText.toFloatOrNull() ?: band.lo,
                        lo = band.lo, hi = band.hi,
                        markerColor = KlColors.forLevel(gauge.level),
                    )
                }
                val caption = listOfNotNull(gauge.contextLabel, gauge.bandText)
                    .joinToString(" · ")
                if (caption.isNotEmpty()) {
                    Text(caption, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                gauge.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = KlColors.Attention)
                }
                Sparkline(gauge.history)
            }
        }
    }
}

@Composable
private fun TrimsTile(trims: List<GaugeUiState>) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Fuel trims", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                trims.forEach { g ->
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(g.title, style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (g.level == GaugeLevel.UNAVAILABLE) {
                            Text("—", style = MaterialTheme.typography.headlineSmall)
                        } else {
                            GaugeValue(g)
                            g.band?.let { band ->
                                BandBar(
                                    value = g.valueText.toFloatOrNull() ?: band.lo,
                                    lo = band.lo, hi = band.hi,
                                    markerColor = KlColors.forLevel(g.level),
                                )
                            }
                        }
                    }
                }
            }
            trims.firstNotNullOfOrNull { it.hint }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = KlColors.Attention)
            }
        }
    }
}
