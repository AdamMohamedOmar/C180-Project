package com.kompressorlink.app.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.ui.theme.KlColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Spec §8.5 row wording. Pure — JVM-tested. */
fun formatDueLine(info: DueInfo): String = when (info.state) {
    DueState.NEVER_LOGGED -> "When did you last do this?"
    DueState.KM_UNTRACKED -> "Enter your odometer to activate"
    DueState.OVERDUE -> {
        val parts = mutableListOf<String>()
        info.remainingKm?.takeIf { it < 0 }?.let { parts += "${-it} km" }
        info.remainingDays?.takeIf { it < 0 }?.let { parts += "${-it} days" }
        "Overdue by ${parts.joinToString(" · ")}"
    }
    DueState.DUE_SOON, DueState.OK -> {
        val parts = mutableListOf<String>()
        info.remainingKm?.takeIf { it >= 0 }?.let { parts += "$it km" }
        info.remainingDays?.takeIf { it >= 0 }?.let { parts += "$it days" }
        if (parts.isEmpty()) "Interval not computable" else parts.joinToString(" or ") + " left"
    }
}

private fun dueColor(state: DueState) = when (state) {
    DueState.OVERDUE -> KlColors.Attention
    DueState.DUE_SOON -> KlColors.Watch
    DueState.NEVER_LOGGED, DueState.KM_UNTRACKED -> KlColors.Neutral
    DueState.OK -> KlColors.Ok
}

private val dateFormat = DateTimeFormatter.ofPattern("MMM yyyy")
private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormat)

@Composable
fun MaintenanceScreen(viewModel: MaintenanceViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showOdometerDialog by remember { mutableStateOf(false) }
    var logTarget by remember { mutableStateOf<MaintenanceItemEntity?>(null) }
    var detailTargetId by remember { mutableStateOf<Long?>(null) }
    var showItemPicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showItemPicker = true }) {
                Text("+ Log service")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item {
                OdometerCard(state.odometer, onUpdate = { showOdometerDialog = true })
            }
            state.message?.let { msg ->
                item {
                    Text(msg, color = KlColors.Attention,
                         style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            items(state.items, key = { it.item.id }) { row ->
                ItemRow(row, onClick = { detailTargetId = row.item.id })
            }
            item {
                TextButton(onClick = { showAddDialog = true }) { Text("＋ Add custom item") }
            }
            item {
                Text(
                    "Reference-guide intervals, not WIS gospel — confirm safety-critical values against WIS for your VIN.",
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAddDialog) {
        AddItemDialog(
            onConfirm = { name, category, km, months, note ->
                viewModel.addCustomItem(name, category, km, months, note)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
    if (showOdometerDialog) {
        NumberDialog(
            title = "Update odometer",
            label = "Current odometer (km)",
            onConfirm = { km -> viewModel.updateOdometer(km); showOdometerDialog = false },
            onDismiss = { showOdometerDialog = false },
        )
    }
    if (showItemPicker) {
        ItemPickerDialog(
            items = state.items.map { it.item },
            onPick = { item -> showItemPicker = false; logTarget = item },
            onDismiss = { showItemPicker = false },
        )
    }
    logTarget?.let { item ->
        LogServiceDialog(
            item = item,
            defaultKm = state.odometer.estimatedKm,
            onConfirm = { km, note ->
                viewModel.logService(item.id, System.currentTimeMillis(), km, note)
                logTarget = null
            },
            onDismiss = { logTarget = null },
        )
    }
    detailTargetId?.let { id ->
        val row = state.items.firstOrNull { it.item.id == id }
        if (row != null) {
            ItemDetailDialog(
                row = row,
                viewModel = viewModel,
                onLog = { detailTargetId = null; logTarget = row.item },
                onDismiss = { detailTargetId = null },
            )
        } else {
            // Item vanished (e.g. deleted) while the dialog was conceptually open.
            detailTargetId = null
        }
    }
}

@Composable
private fun OdometerCard(odometer: OdometerUi, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Odometer", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (odometer.estimatedKm == null) {
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Enter your odometer to activate km tracking",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                Text("≈ ${odometer.estimatedKm} km", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Last entered ${odometer.anchorKm} km · ${formatDate(odometer.anchorEpochMs!!)}" +
                        " · +${odometer.estimatedDeltaKm} km estimated from drives",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onUpdate, modifier = Modifier.padding(top = 8.dp)) {
                Text("Update odometer")
            }
        }
    }
}

@Composable
private fun ItemRow(row: MaintenanceItemUi, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dueColor(row.info.state), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(row.item.name, style = MaterialTheme.typography.titleSmall)
                if (!row.item.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Text("(off)", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(formatDueLine(row.info), style = MaterialTheme.typography.bodySmall,
                     color = dueColor(row.info.state))
            }
            row.lastLog?.let { log ->
                Text(
                    "Last: ${log.km?.let { "$it km · " } ?: ""}${formatDate(log.epochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.info.kmUntracked && row.info.state != DueState.KM_UNTRACKED) {
                Text("km tracking needs an odometer entry",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(row.item.note, style = MaterialTheme.typography.bodySmall,
                 fontStyle = FontStyle.Italic,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberDialog(title: String, label: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.filter(Char::isDigit) },
                              label = { Text(label) })
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemPickerDialog(
    items: List<MaintenanceItemEntity>,
    onPick: (MaintenanceItemEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which service?") },
        text = {
            // heightIn guard: an unbounded LazyColumn inside a dialog can
            // blow up measurement on some Compose versions.
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items, key = { it.id }) { item ->
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogServiceDialog(
    item: MaintenanceItemEntity,
    defaultKm: Int?,
    onConfirm: (km: Int?, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kmText by remember { mutableStateOf(defaultKm?.toString() ?: "") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log: ${item.name}") },
        text = {
            Column {
                Text("Date: today", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = kmText, onValueChange = { kmText = it.filter(Char::isDigit) },
                                  label = { Text("Odometer at service (km, optional)") })
                OutlinedTextField(value = note, onValueChange = { note = it },
                                  label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kmText.toIntOrNull(), note.ifBlank { null }) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemDetailDialog(
    row: MaintenanceItemUi,
    viewModel: MaintenanceViewModel,
    onLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val historyFlow = remember(row.item.id) { viewModel.historyFor(row.item.id) }
    val logs by historyFlow.collectAsState(initial = emptyList())
    // Spec §8.5: intervals are editable per item (builtins included — only
    // deletion is builtin-restricted). Emptying BOTH fields is rejected by
    // the repository and surfaces via state.message.
    var kmText by remember(row.item.id) { mutableStateOf(row.item.intervalKm?.toString() ?: "") }
    var monthsText by remember(row.item.id) { mutableStateOf(row.item.intervalMonths?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.item.name) },
        text = {
            Column {
                Row {
                    OutlinedTextField(
                        value = kmText, onValueChange = { kmText = it.filter(Char::isDigit) },
                        label = { Text("Interval (km)") }, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = monthsText, onValueChange = { monthsText = it.filter(Char::isDigit) },
                        label = { Text("Interval (months)") }, modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = {
                    viewModel.updateItem(row.item.copy(
                        intervalKm = kmText.toIntOrNull(),
                        intervalMonths = monthsText.toIntOrNull(),
                    ))
                }) { Text("Save intervals") }
                Text("[${row.item.confidence}] ${row.item.note}",
                     style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = row.item.enabled, onCheckedChange = { on ->
                        viewModel.updateItem(row.item.copy(enabled = on))
                    })
                }
                Text("History", style = MaterialTheme.typography.labelMedium,
                     modifier = Modifier.padding(top = 8.dp))
                if (logs.isEmpty()) {
                    Text("No services logged yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    logs.forEach { log ->
                        Text(
                            "• ${formatDate(log.epochMs)}${log.km?.let { " · $it km" } ?: ""}" +
                                (log.note?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!row.item.builtin) {
                    TextButton(onClick = {
                        viewModel.deleteCustomItem(row.item.id); onDismiss()
                    }) { Text("Delete custom item", color = KlColors.Attention) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLog) { Text("Mark done…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddItemDialog(
    onConfirm: (name: String, category: String, km: Int?, months: Int?, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("INSPECTION") }
    var km by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var categoryMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom item") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") })
                TextButton(onClick = { categoryMenu = true }) { Text("Category: $category") }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    listOf("ENGINE", "TRANSMISSION", "BRAKES", "FLUIDS", "INSPECTION").forEach { c ->
                        DropdownMenuItem(text = { Text(c) },
                                         onClick = { category = c; categoryMenu = false })
                    }
                }
                Row {
                    OutlinedTextField(value = km, onValueChange = { km = it.filter(Char::isDigit) },
                                      label = { Text("km") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = months, onValueChange = { months = it.filter(Char::isDigit) },
                                      label = { Text("months") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = note, onValueChange = { note = it },
                                  label = { Text("Why it matters (note)") })
                Text("Set a km interval, a month interval, or both.",
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (km.toIntOrNull() != null || months.toIntOrNull() != null),
                onClick = { onConfirm(name, category, km.toIntOrNull(), months.toIntOrNull(), note) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
