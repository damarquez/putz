package com.damarquez.putz.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.repository.SyncProgress

/** Shared between CalibreTransfersScreen and Settings > Libraries & Sync — both trigger the same
 *  CalibreRepository.performFullSync() and observe its single [SyncProgress] flow, so whichever
 *  screen the user is on shows what the sync is actually doing right now instead of an opaque
 *  spinner (see CONTRACTS.md discussion: previously a stuck/slow sync gave zero visibility into
 *  which phase — or which item within a phase — it was stuck on). */
@Composable
fun SyncProgressBanner(progress: SyncProgress?, modifier: Modifier = Modifier) {
    if (progress == null) return
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = progress.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val current = progress.current
            val total = progress.total
            if (current != null && total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}
