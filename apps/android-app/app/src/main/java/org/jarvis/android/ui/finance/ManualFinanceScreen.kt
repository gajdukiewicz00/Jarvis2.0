package org.jarvis.android.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.local.PendingItem
import java.util.UUID

/**
 * Parses the raw amount text and builds the finance-entry JSON payload used for the
 * offline sync queue. Returns `null` when [rawAmount] is not a valid number so the
 * caller can surface a validation error instead of queuing a bad entry.
 *
 * Pure function — extracted from the [ManualFinanceScreen] Save button's onClick
 * lambda so the parsing/JSON-building logic can be unit tested directly.
 */
fun buildFinanceEntryPayload(
    rawAmount: String,
    currency: String,
    category: String,
    description: String
): String? {
    val amount = rawAmount.toDoubleOrNull() ?: return null
    return buildJsonObject {
        put("amount", JsonPrimitive(amount))
        put("currency", JsonPrimitive(currency))
        put("category", JsonPrimitive(category))
        put("description", JsonPrimitive(description))
        put("type", JsonPrimitive("EXPENSE"))
    }.toString()
}

/**
 * Phase 12 — manual finance entry. Saves the entry into the offline
 * Room queue immediately; SyncWorker uploads in the background. Works
 * with no connectivity.
 */
@Composable
fun ManualFinanceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EUR") }
    var category by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    val dao = remember { JarvisDatabase.get(context).pendingItems() }
    val recent by dao.recent(20).let { flow ->
        val state = remember { mutableStateOf(emptyList<PendingItem>()) }
        LaunchedEffect(flow) { flow.collect { state.value = it } }
        state
    }

    Column(modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(8.dp)) {
        Text("Manual finance entry", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") })
        OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("Currency") })
        OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") })
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
        Button(onClick = {
            val payloadJson = buildFinanceEntryPayload(amount, currency, category, description)
            if (payloadJson == null) { feedback = "amount must be numeric"; return@Button }
            scope.launch {
                dao.upsert(PendingItem(
                    id = UUID.randomUUID().toString(),
                    kind = "FINANCE_ENTRY",
                    payloadJson = payloadJson,
                    createdAtEpochMs = System.currentTimeMillis()
                ))
                feedback = "queued — will sync on next worker run"
                amount = ""; category = ""; description = ""
            }
        }) { Text("Save") }
        if (feedback.isNotBlank()) Text(feedback)

        Text("Recent (queued + synced):", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        recent.forEach {
            val tag = if (it.syncedAtEpochMs != null) "✓" else if (it.lastError != null) "!" else "…"
            Text("$tag  ${it.kind}  ${it.payloadJson.take(80)}")
        }
    }
}
