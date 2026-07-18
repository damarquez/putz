package com.damarquez.putz.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.LanConnectionEntity

@Composable
fun ButtonRow(
    label: String,
    onClick: () -> Unit,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun RadioRow(
    label: String,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared picker used by the put.io/Plex/Plexamp "select a LAN connection" dialogs. */
@Composable
fun LanConnectionPickerDialog(
    title: String,
    connections: List<LanConnectionEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (connections.isEmpty()) {
                    Text("No LAN connections configured. Add one in LAN Files settings first.")
                } else {
                    connections.forEach { conn ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(conn.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = conn.id == selectedId,
                                onClick = { onSelect(conn.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(conn.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "\\\\${conn.host}\\${conn.shareName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
