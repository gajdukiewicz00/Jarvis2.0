package org.jarvis.android.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phase 12 — thin OkHttp client speaking to either the on-prem
 * sync-service (when the phone is on the home LAN) or the cloud-relay
 * (when remote). The wire format is identical; only the URL changes.
 */
class SyncClient(
    private val baseUrl: String,
    timeoutSeconds: Long = 5
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class PairingInitResponse(val pairingNonceB64: String, val serverKexPubB64: String)

    @Serializable
    data class PairingRequest(
        val deviceLabel: String,
        val identityPubB64: String,
        val kexPubB64: String,
        val pairingNonceB64: String,
        val identitySigB64: String
    )

    @Serializable
    data class PairingResponse(
        val serverKexPubB64: String,
        val routingId: String,
        val senderDeviceId: String,
        val pairedAt: String
    )

    @Serializable
    data class SyncEnvelope(
        val version: Int,
        val routingId: String,
        val senderDeviceId: String,
        val nonceB64: String,
        val ciphertextB64: String,
        val occurredAtClient: String
    )

    fun pairingInit(): PairingInitResponse {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/sync/pairing/init")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "pairing init failed: ${resp.code}" }
            return json.decodeFromString<PairingInitResponse>(resp.body!!.string())
        }
    }

    fun pairingComplete(pr: PairingRequest): PairingResponse {
        val body = json.encodeToString(PairingRequest.serializer(), pr)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/v1/sync/pairing/complete")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "pairing complete failed: ${resp.code} ${resp.body?.string()}" }
            return json.decodeFromString<PairingResponse>(resp.body!!.string())
        }
    }

    /** @return HTTP status code; 202 = accepted, others surface to the caller for retry/backoff. */
    fun postBlob(envelope: SyncEnvelope): Int {
        val body = json.encodeToString(SyncEnvelope.serializer(), envelope)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/v1/sync/blobs")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp -> return resp.code }
    }
}
