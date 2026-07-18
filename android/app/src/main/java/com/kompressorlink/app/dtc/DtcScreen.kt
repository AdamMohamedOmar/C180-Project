package com.kompressorlink.app.dtc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.components.StatusChip
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun DtcScreen(viewModel: DtcViewModel) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(
                if (!state.hasReport) "No DTC data received yet"
                else "${state.stored.size} stored · ${state.pending.size} pending",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        item {
            Text("Stored codes", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 12.dp))
        }
        if (state.hasReport && state.stored.isEmpty()) {
            item { Text("No stored codes — nothing to worry about here.",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.stored, key = { "s-${it.code}" }) { dtc ->
            val key = "s-${dtc.code}"
            DtcCard(dtc, expanded = key in state.expandedCodes) { viewModel.toggle(key) }
        }

        item {
            Text("Pending codes", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (state.hasReport && state.pending.isEmpty()) {
            item { Text("No pending codes.", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.pending, key = { "p-${it.code}" }) { dtc ->
            val key = "p-${dtc.code}"
            DtcCard(dtc, expanded = key in state.expandedCodes) { viewModel.toggle(key) }
        }

        item {
            // Read-only by design: Mode 04 clear-DTC remains deferred
            // (erases evidence, resets readiness monitors).
            Text(
                "Clearing codes is not available in this version.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

private fun urgencyColor(urgency: DtcUrgency?) = when (urgency) {
    DtcUrgency.INFO -> KlColors.Neutral
    DtcUrgency.CHECK_SOON -> KlColors.Watch
    DtcUrgency.URGENT, DtcUrgency.STOP -> KlColors.Attention
    null -> KlColors.Neutral
}

@Composable
private fun DtcCard(dtc: DtcExplanation, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onToggle)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(dtc.code, style = MaterialTheme.typography.titleMedium,
                     fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                StatusChip(dtc.urgency?.label ?: "Unknown urgency", urgencyColor(dtc.urgency))
            }
            Text(dtc.title, style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp)) {
                StatusChip(dtc.layer.chip, KlColors.Neutral)
                dtc.confidence?.let {
                    Spacer(Modifier.width(6.dp))
                    StatusChip("Confidence: $it", KlColors.Neutral)
                }
            }
            if (!expanded) {
                Text(dtc.meaning, style = MaterialTheme.typography.bodySmall,
                     maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text("Tap for details ▾", style = MaterialTheme.typography.labelSmall,
                     modifier = Modifier.padding(top = 4.dp))
            } else {
                Section("What's happening", dtc.meaning)
                dtc.canIDrive?.let { Section("Can I keep driving?", it) }
                if (dtc.checkFirst.isNotEmpty()) {
                    Text("Check first", style = MaterialTheme.typography.labelMedium,
                         modifier = Modifier.padding(top = 8.dp))
                    dtc.checkFirst.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium,
                             modifier = Modifier.padding(top = 2.dp))
                    }
                }
                dtc.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(label: String, body: String) {
    Text(label, style = MaterialTheme.typography.labelMedium,
         modifier = Modifier.padding(top = 8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium,
         modifier = Modifier.padding(top = 2.dp))
}
