package org.jarvis.desktop.service.wake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Production [WakeSidecarClient] over okhttp (already a project dependency, used
 * by `PcControlWebSocketClient`). Short requests use a small-timeout client; the
 * SSE stream uses a separate readTimeout=0 client and reads `text/event-stream`
 * lines directly (`data: {json}`) on a daemon thread — no okhttp-sse module
 * needed. The provider depends only on [WakeSidecarClient], so this class is
 * never referenced from tests.
 *
 * Endpoints (relative to [baseUrl]):
 *   GET  /health       → {"status":"UP"|..., "engine": "..."}
 *   GET  /devices      → {"devices":["..."]}  (or a bare JSON array)
 *   POST /start        → 200 on success; 503 / {"error":"vosk_not_installed"}
 *   POST /stop         → 200 on success
 *   GET  /events       → text/event-stream of `data: {json}` lines
 *   GET  /diagnostics  → {"installed":bool,"models":[...],...}
 */
class OkHttpWakeSidecarClient(
    baseUrl: String,
    private val shortClient: OkHttpClient = defaultShortClient(),
    private val streamClient: OkHttpClient = defaultStreamClient()
) : WakeSidecarClient {

    private val logger = LoggerFactory.getLogger(OkHttpWakeSidecarClient::class.java)
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override fun health(): SidecarHealth {
        return try {
            shortClient.newCall(Request.Builder().url("$base/health").get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return SidecarHealth(false, detail = "HTTP ${resp.code}")
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val status = obj?.get("status")?.jsonPrimitive?.contentOrNull
                val up = obj?.get("up")?.jsonPrimitive?.booleanOrNull
                    ?: status?.equals("UP", ignoreCase = true)
                    ?: true
                SidecarHealth(up, engine = obj?.get("engine")?.jsonPrimitive?.contentOrNull, detail = status)
            }
        } catch (e: Exception) {
            SidecarHealth(false, detail = e.message)
        }
    }

    override fun devices(): List<String> {
        return try {
            shortClient.newCall(Request.Builder().url("$base/devices").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val element = json.parseToJsonElement(resp.body?.string().orEmpty())
                val array: JsonArray = when {
                    element is JsonArray -> element
                    else -> element.jsonObject["devices"]?.jsonArray ?: return emptyList()
                }
                // Each /devices entry is an OBJECT {id,name,...}; older/fake shapes may be a
                // bare string. Extract the display name from either form.
                array.mapNotNull { deviceDisplayName(it) }
            }
        } catch (e: Exception) {
            logger.debug("wake.sidecar.devices error: {}", e.message)
            emptyList()
        }
    }

    override fun startEngine(request: StartEngineRequest): StartEngineResponse {
        val payload = buildJsonObject {
            put("device", request.device)
            put("model", request.model)
            put("threshold", request.threshold)
            put("engine", request.engine)
        }.toString()
        return try {
            val httpReq = Request.Builder()
                .url("$base/start")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            shortClient.newCall(httpReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val error = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                StartEngineResponse(started = resp.isSuccessful, statusCode = resp.code, error = error)
            }
        } catch (e: Exception) {
            StartEngineResponse(false, statusCode = 0, error = e.message)
        }
    }

    override fun stopEngine(): Boolean {
        return try {
            val httpReq = Request.Builder()
                .url("$base/stop")
                .post(EMPTY_BODY.toRequestBody(JSON_MEDIA))
                .build()
            shortClient.newCall(httpReq).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            logger.debug("wake.sidecar.stop error: {}", e.message)
            false
        }
    }

    override fun openEvents(onEvent: (String) -> Unit, onError: (Throwable) -> Unit): Closeable {
        val request = Request.Builder()
            .url("$base/events")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call: Call = streamClient.newCall(request)

        val reader = Thread {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError(IllegalStateException("events HTTP ${resp.code}"))
                        return@use
                    }
                    val source = resp.body?.source() ?: run {
                        onError(IllegalStateException("events: empty body"))
                        return@use
                    }
                    while (!call.isCanceled()) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trimEnd('\r')
                        if (trimmed.startsWith(SSE_DATA_PREFIX)) {
                            val payload = trimmed.substring(SSE_DATA_PREFIX.length).trimStart()
                            if (payload.isNotEmpty()) onEvent(payload)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!call.isCanceled()) onError(e)
            }
        }.apply {
            name = "jarvis-wake-sse-reader"
            isDaemon = true
            start()
        }

        return Closeable {
            call.cancel()
            reader.interrupt()
        }
    }

    override fun diagnostics(): SidecarDiagnosticsData? {
        return try {
            shortClient.newCall(Request.Builder().url("$base/diagnostics").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                SidecarDiagnosticsData(
                    installed = obj["installed"]?.jsonPrimitive?.booleanOrNull,
                    models = obj["models"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    // selectedDevice is an OBJECT {id,name,...} from the real sidecar (or a bare
                    // string in fakes) — calling .jsonPrimitive on the object threw and nulled the
                    // whole diagnostics; extract the name from either shape.
                    selectedDevice = obj["selectedDevice"]?.let { deviceDisplayName(it) },
                    listening = obj["listening"]?.jsonPrimitive?.booleanOrNull ?: false,
                    lastWakeScore = obj["lastWakeScore"]?.jsonPrimitive?.doubleOrNull,
                    lastWakeDetectedAt = obj["lastWakeDetectedAt"]?.jsonPrimitive?.contentOrNull,
                    lastError = obj["lastError"]?.jsonPrimitive?.contentOrNull
                )
            }
        } catch (e: Exception) {
            logger.debug("wake.sidecar.diagnostics error: {}", e.message)
            null
        }
    }

    /** Extract a device display name from a /devices or selectedDevice element that may be
     *  an object {id,name,...} (real sidecar) or a bare JSON string (fakes/legacy). */
    private fun deviceDisplayName(element: JsonElement): String? = when (element) {
        is JsonObject -> element["name"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    companion object {
        private const val SSE_DATA_PREFIX = "data:"
        private const val EMPTY_BODY = "{}"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultShortClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        fun defaultStreamClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: keep the stream open indefinitely.
            .build()
    }
}
