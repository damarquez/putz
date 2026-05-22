package com.damarquez.putz.ui.files

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SUBTITLE_LANGUAGES = listOf(
    "en" to "English",
    "pt" to "Portuguese",
)

@Composable
fun PlexLanguagePicker(
    title: String = "Subtitle language",
    disabledLanguages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (languageCode: String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SUBTITLE_LANGUAGES.forEach { (code, label) ->
                    val disabled = code in disabledLanguages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !disabled) { selected = code }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == code,
                            onClick = { selected = code },
                            enabled = !disabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        if (disabled) {
                            Spacer(Modifier.width(4.dp))
                            Text("(already added)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onConfirm(it) } },
                enabled = selected != null,
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
