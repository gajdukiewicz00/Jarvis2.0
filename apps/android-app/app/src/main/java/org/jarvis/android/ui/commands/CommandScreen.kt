package org.jarvis.android.ui.commands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.local.PendingItem
import java.util.UUID

/**
 * Phase 12 — phone command sender.
 *
 * <p>Whatever the user types is queued as a {@code COMMAND_INTENT}.
 * SyncWorker uploads it to the on-prem sync-service, which dispatches
 * to {@code orchestrator/execute}. The orchestrator's existing
 * Phase 5 risk classifier + confirmation pipeline gates risky
 * commands the same way it gates voice commands — mobile is just
 * another input channel, never a bypass.</p>
 */
@Composable
fun CommandScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    val dao = remember { JarvisDatabase.get(context).pendingItems() }

    Column(modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text("Send a command to Jarvis", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Command (free text)") }
        )
        Button(onClick = {
            if (text.isBlank()) { feedback = "type a command first"; return@Button }
            val payload = buildJsonObject {
                put("text", JsonPrimitive(text))
                put("language", JsonPrimitive("ru"))
            }
            scope.launch {
                dao.upsert(PendingItem(
                    id = UUID.randomUUID().toString(),
                    kind = "COMMAND_INTENT",
                    payloadJson = payload.toString(),
                    createdAtEpochMs = System.currentTimeMillis()
                ))
                feedback = "queued — risk gate runs server-side"
                text = ""
            }
        }) { Text("Queue") }
        if (feedback.isNotBlank()) Text(feedback)
        Text("Risk-classified or destructive commands trigger a confirmation prompt on the desktop.",
             style = MaterialTheme.typography.bodySmall)
    }
}
