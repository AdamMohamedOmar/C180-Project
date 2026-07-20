package com.kompressorlink.app.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import com.kompressorlink.app.ui.components.StatusChip
import com.kompressorlink.app.ui.theme.KlColors
import java.util.Locale

@Composable
fun RidesScreen(viewModel: RidesViewModel) {
    val state by viewModel.state.collectAsState()
    val files by viewModel.files.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Button(onClick = viewModel::sync, enabled = !state.syncing) {
            Text(if (state.syncing) "Syncing…" else "Sync now")
        }
        state.phase?.let { phase ->
            Text(phase, style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.padding(top = 8.dp))
        }
        state.error?.let { error ->
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .background(KlColors.Attention.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Text(error, color = KlColors.Attention, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (files.isEmpty()) {
            // Honest empty state — no fabricated rows (Untested contract,
            // CLAUDE.md): nothing has synced yet, say exactly that.
            Text(
                "No rides synced yet. Start the engine, then sync when parked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(files, key = { it.name }) { row -> RideRow(row) }
            }
        }
    }
}

@Composable
private fun RideRow(row: RideFileEntity) {
    val status = parseStatus(row.status)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        String.format(Locale.US, "%.1f KB", row.sizeBytes / 1024.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(statusLabel(status), statusColor(status))
            }
            if (status == RideFileStatus.DOWNLOADING) {
                val progress = if (row.sizeBytes > 0) {
                    (row.downloadedBytes.toFloat() / row.sizeBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = KlColors.Watch,
                )
            }
            if (status == RideFileStatus.INGESTED && row.sessionId != null) {
                Text(
                    "session #${row.sessionId}",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (status == RideFileStatus.DUPLICATE) {
                Text(
                    "duplicate of a live session",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// row.status is a String (the enum's .name — Room stores it that way, see
// data/db/RideFiles.kt). Parse defensively rather than valueOf()-crash the
// whole screen on an unrecognized value: this exact fallback-on-bad-string
// pattern is already established for stored-string enums in this codebase
// (KompressorLinkApp.kt's DataStoreSourceChoiceStore).
private fun parseStatus(raw: String): RideFileStatus =
    runCatching { RideFileStatus.valueOf(raw) }.getOrDefault(RideFileStatus.PENDING)

private fun statusLabel(status: RideFileStatus): String = when (status) {
    RideFileStatus.PENDING -> "Pending"
    RideFileStatus.DOWNLOADING -> "Downloading"
    RideFileStatus.DOWNLOADED -> "Downloaded"
    RideFileStatus.VERIFIED -> "Verified"
    RideFileStatus.INGESTED -> "Ingested"
    RideFileStatus.DUPLICATE -> "Duplicate"
    RideFileStatus.FAILED -> "Failed"
}

private fun statusColor(status: RideFileStatus): Color = when (status) {
    RideFileStatus.PENDING -> KlColors.Neutral
    RideFileStatus.DOWNLOADING, RideFileStatus.DOWNLOADED -> KlColors.Watch
    RideFileStatus.VERIFIED, RideFileStatus.INGESTED -> KlColors.Ok
    RideFileStatus.DUPLICATE -> KlColors.Neutral
    RideFileStatus.FAILED -> KlColors.Attention
}
