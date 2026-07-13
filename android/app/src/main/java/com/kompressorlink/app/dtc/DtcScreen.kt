package com.kompressorlink.app.dtc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

@Composable
fun DtcScreen(viewModel: DtcViewModel) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Text("Stored codes", style = MaterialTheme.typography.titleMedium) }
        if (!state.hasReport) {
            item { Text("No DTC data received yet.", Modifier.padding(vertical = 8.dp)) }
        } else if (state.stored.isEmpty()) {
            item { Text("No stored codes.", Modifier.padding(vertical = 8.dp)) }
        } else {
            items(state.stored) { DtcCard(it) }
        }

        item {
            Text("Pending codes", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (!state.hasReport) {
            item { Text("—", Modifier.padding(vertical = 8.dp)) }
        } else if (state.pending.isEmpty()) {
            item { Text("No pending codes.", Modifier.padding(vertical = 8.dp)) }
        } else {
            items(state.pending) { DtcCard(it) }
        }

        item {
            // Read-only by design: Mode 04 clear-DTC is the project's only
            // ECU write and is deferred out of Phase 4 (erases evidence,
            // resets readiness monitors — spec, scope decision 3).
            Text(
                "Clearing codes is not available in this version.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun DtcCard(dtc: DtcUi) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (dtc.title != null) "${dtc.code} — ${dtc.title}" else dtc.code,
                style = MaterialTheme.typography.titleSmall,
            )
            if (dtc.causes.isEmpty()) {
                Text("No W203-specific interpretation for this code.",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                dtc.causes.forEachIndexed { i, cause ->
                    Text("${i + 1}. $cause", style = MaterialTheme.typography.bodyMedium)
                }
            }
            dtc.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
            dtc.confidence?.let {
                Text("Confidence: $it", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
