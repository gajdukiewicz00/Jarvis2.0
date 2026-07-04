package org.jarvis.android.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarvis.android.data.health.HealthConnectManager
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.local.PendingItem
import java.time.LocalDate
import java.util.UUID

/**
 * Health tab — reads sleep + steps from Health Connect on-device and queues a
 * HEALTH_ENTRY for E2E sync to the home server (life-tracker). Local-first:
 * no cloud account, nothing leaves the phone until the user taps sync.
 */
@Composable
fun HealthScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { HealthConnectManager(context) }
    val dao = remember { JarvisDatabase.get(context).pendingItems() }
    var status by remember { mutableStateOf(if (manager.isAvailable()) "Ready" else "Health Connect not available") }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status = if (granted.containsAll(manager.permissions)) "Access granted" else "Access denied"
    }

    Column(modifier.padding(16.dp)) {
        Text("Health (Health Connect)")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { permissionLauncher.launch(manager.permissions) }) {
            Text("Grant health access")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                runCatching {
                    if (!manager.hasAllPermissions()) {
                        status = "Grant health access first"
                        return@launch
                    }
                    val snapshot = manager.read()
                    val payload = buildJsonObject {
                        put("sleepHours", JsonPrimitive(snapshot.sleepHours))
                        put("steps", JsonPrimitive(snapshot.steps))
                        put("date", JsonPrimitive(LocalDate.now().toString()))
                    }
                    dao.upsert(
                        PendingItem(
                            id = UUID.randomUUID().toString(),
                            kind = "HEALTH_ENTRY",
                            payloadJson = payload.toString(),
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                    status = "Queued: sleep=%.1fh steps=%d".format(snapshot.sleepHours, snapshot.steps)
                }.onFailure { status = "Error: ${it.message}" }
            }
        }) {
            Text("Sync health now")
        }
        Spacer(Modifier.height(12.dp))
        Text(status)
    }
}
