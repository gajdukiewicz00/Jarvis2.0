package org.jarvis.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jarvis.android.data.crypto.SyncCryptoKt
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.remote.SyncClient
import java.time.Instant

/**
 * Phase 12 — drains the offline queue when WorkManager schedules us.
 *
 * <p>For each pending item:</p>
 * <ol>
 *   <li>Build a SyncPayload JSON (kind + clientNonce + data)</li>
 *   <li>Seal with the per-pairing AEAD key</li>
 *   <li>POST as a SyncEnvelope to the on-prem sync-service (or
 *       cloud-relay when the LAN is unreachable — base URL switches
 *       are owned by {@link PairingState})</li>
 *   <li>On 202 Accepted, mark synced; on 4xx/5xx, mark failed +
 *       leave for next backoff</li>
 * </ol>
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val tag = "JarvisSyncWorker"
    private val json = Json { encodeDefaults = true }

    override suspend fun doWork(): Result {
        val state = PairingState(applicationContext)
        if (!state.isPaired()) {
            Log.i(tag, "no pairing — nothing to sync")
            return Result.success()
        }
        val baseUrl = state.baseUrl() ?: return Result.success()
        val routingId = state.routingId()!!
        val deviceId = state.senderDeviceId()!!
        val sessionKey = SyncCryptoKt.unb64(state.sessionKeyB64()!!)

        val client = SyncClient(baseUrl)
        val dao = JarvisDatabase.get(applicationContext).pendingItems()
        val pending = dao.pending()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (item in pending) {
            val payload: JsonElement = buildJsonObject {
                put("kind", JsonPrimitive(item.kind))
                put("clientNonce", JsonPrimitive(item.id))
                put("clientOccurredAt", JsonPrimitive(Instant.ofEpochMilli(item.createdAtEpochMs).toString()))
                put("data", json.parseToJsonElement(item.payloadJson) as JsonObject)
            }
            val plaintext = json.encodeToString(JsonElement.serializer(), payload).toByteArray()
            val nonce = SyncCryptoKt.randomNonce()
            val nonceB64 = SyncCryptoKt.b64(nonce)
            val aad = "1|$routingId|$deviceId|$nonceB64".toByteArray()
            val ciphertext = SyncCryptoKt.seal(sessionKey, nonce, aad, plaintext)
            val envelope = SyncClient.SyncEnvelope(
                version = 1,
                routingId = routingId,
                senderDeviceId = deviceId,
                nonceB64 = nonceB64,
                ciphertextB64 = SyncCryptoKt.b64(ciphertext),
                occurredAtClient = Instant.now().toString()
            )
            try {
                val code = client.postBlob(envelope)
                if (code in 200..299) {
                    dao.markSynced(item.id, System.currentTimeMillis())
                } else {
                    dao.markFailed(item.id, System.currentTimeMillis(), "http=$code")
                    anyFailed = true
                }
            } catch (e: Exception) {
                Log.w(tag, "sync failed for ${item.id}: ${e.message}")
                dao.markFailed(item.id, System.currentTimeMillis(), e.message ?: e::class.simpleName.orEmpty())
                anyFailed = true
            }
        }
        return if (anyFailed) Result.retry() else Result.success()
    }
}
