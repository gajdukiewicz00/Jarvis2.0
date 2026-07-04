package org.jarvis.android.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jarvis.android.data.local.JarvisDatabase

/**
 * Phase 12 — read-only stats from the offline queue.
 *
 * <p>Pass 1 shows local counters only (queued / synced / failed). Pass 2
 * pulls a periodic snapshot of the desktop's life-map summary into a
 * Room cache table and renders charts against it.</p>
 */
@Composable
fun StatisticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember { JarvisDatabase.get(context).pendingItems() }
    var queued by remember { mutableStateOf(0) }
    var synced by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        dao.recent(500).collect { list ->
            queued = list.count { it.syncedAtEpochMs == null && it.lastError == null }
            synced = list.count { it.syncedAtEpochMs != null }
            failed = list.count { it.syncedAtEpochMs == null && it.lastError != null }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(8.dp)) {
        Text("Sync stats", style = MaterialTheme.typography.titleMedium)
        Text("Queued (waiting): $queued")
        Text("Synced to Jarvis: $synced")
        Text("Failed (will retry): $failed")
        Text("Pass 2 will add finance / activity charts pulled from the desktop's life-map summary.",
             style = MaterialTheme.typography.bodySmall)
    }
}
