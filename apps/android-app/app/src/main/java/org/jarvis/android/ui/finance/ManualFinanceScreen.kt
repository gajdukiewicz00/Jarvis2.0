package org.jarvis.android.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.local.PendingItem
import org.jarvis.android.data.local.PendingItemDao
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
    // Memoized by dao identity (see [RecentItemsFlowCache]) so recomposition triggered by
    // typing in the fields above does not re-issue the underlying Room query on every
    // keystroke (finding #54).
    val recentItemsFlowCache = remember { RecentItemsFlowCache() }
    val recent by recentItemsFlowCache.flowFor(dao).collectAsState(initial = emptyList())

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

/**
 * Caches the [Flow] returned by [PendingItemDao.recent], keyed on DAO instance identity, so
 * repeated calls with the same [dao] return the *same* Flow instance instead of a fresh one.
 *
 * Extracted as a plain class (rather than relying only on inline `remember` inside the
 * composable) so the "same key -> same Flow instance, DAO queried at most once per key" fix for
 * finding #54 can be unit tested on plain JVM, without a Compose runtime. [ManualFinanceScreen]
 * holds one instance of this class via `remember { RecentItemsFlowCache() }` — since state
 * hoisted higher up (amount/currency/category/description) triggers recomposition of the whole
 * composable body on every keystroke, calling `dao.recent(20)` directly (uncached) there
 * produced a *new* Flow object each time, which in turn made downstream Flow collection
 * (`LaunchedEffect`/`collectAsState`, keyed on that Flow's identity) cancel and re-subscribe the
 * underlying Room query on every keystroke instead of once.
 */
class RecentItemsFlowCache {
    private var cachedDao: PendingItemDao? = null
    private var cachedFlow: Flow<List<PendingItem>>? = null

    fun flowFor(dao: PendingItemDao, limit: Int = 20): Flow<List<PendingItem>> {
        val existing = cachedFlow
        if (cachedDao === dao && existing != null) return existing
        return dao.recent(limit).also {
            cachedDao = dao
            cachedFlow = it
        }
    }
}
