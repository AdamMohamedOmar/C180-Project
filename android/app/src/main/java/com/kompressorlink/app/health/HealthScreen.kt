package com.kompressorlink.app.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Demo data", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.demoMode, onCheckedChange = viewModel::setDemoMode)
            }
        }
        if (state.demoMode) {
            item {
                // The loud, unmissable quarantine ribbon (spec §8.3).
                Box(
                    Modifier.fillMaxWidth().background(KlColors.Watch)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DEMO DATA — simulated history",
                         style = MaterialTheme.typography.labelMedium,
                         color = Color.Black)
                }
            }
        }

        item { VerdictCard(state.verdict) }

        state.emptyState?.let { empty ->
            item {
                Text(empty, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        if (state.emptyState == null) {
            items(state.cards, key = { it.subsystem.name }) { card -> SubsystemRow(card) }
        }

        state.driftGateFooter?.let { footer ->
            item {
                Text(footer, style = MaterialTheme.typography.bodySmall,
                     fontStyle = FontStyle.Italic,
                     modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        item {
            Text("Warnings", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (state.warnings.isEmpty()) {
            item { Text("No warnings recorded.", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.warnings, key = { it.id }) { warning ->
            WarningRow(warning, onAcknowledge = { viewModel.acknowledge(warning.id) })
        }
    }
}

@Composable
private fun VerdictCard(verdict: OverallVerdict) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Box(Modifier.width(6.dp).height(72.dp).background(KlColors.forStatus(verdict.status)))
            Column(Modifier.padding(12.dp)) {
                Text(verdict.headline, style = MaterialTheme.typography.titleLarge)
                Text(verdict.subline, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubsystemRow(card: SubsystemCard) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(KlColors.forStatus(card.status), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(card.subsystem.displayName, style = MaterialTheme.typography.titleSmall)
                card.driftArrow?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it.toString(), style = MaterialTheme.typography.titleSmall,
                         color = KlColors.Watch)
                }
                Spacer(Modifier.weight(1f))
                Text(card.status.label, style = MaterialTheme.typography.labelMedium,
                     color = KlColors.forStatus(card.status))
            }
            Text(card.reason, style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.padding(top = 4.dp))
            card.baselineGate?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.footnote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WarningRow(warning: WarningUi, onAcknowledge: () -> Unit) {
    val color = if (warning.level == "ATTENTION") KlColors.Attention else KlColors.Watch
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(
                    if (warning.acknowledged) KlColors.Neutral else color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(warning.timeText, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(warning.title, style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 4.dp))
            Text(warning.detail, style = MaterialTheme.typography.bodySmall)
            if (!warning.acknowledged) {
                TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
            } else {
                Text("acknowledged", style = MaterialTheme.typography.labelSmall,
                     fontStyle = FontStyle.Italic,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
