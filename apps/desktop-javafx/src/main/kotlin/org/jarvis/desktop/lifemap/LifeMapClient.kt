package org.jarvis.desktop.lifemap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate

/**
 * Phase 11 — read-only HTTP client for the life-tracker life-map endpoints.
 *
 * <p>Used by the JavaFX panel ViewModels. Returns parsed JSON nodes to
 * keep the binding layer free from a hard schema dependency on
 * life-tracker DTOs (the panel renders best-effort: missing keys
 * degrade to "—" instead of crashing the UI).</p>
 */
class LifeMapClient(
    private val baseUrl: String,
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val log = LoggerFactory.getLogger(LifeMapClient::class.java)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build()

    fun fetchSummary(userId: String, date: LocalDate? = null): JsonNode? =
        get("/api/v1/life-map/summary?userId=$userId" + dateParam(date))

    fun fetchActivity(userId: String, date: LocalDate? = null): JsonNode? =
        get("/api/v1/life-map/activity?userId=$userId" + dateParam(date))

    fun fetchWarnings(userId: String, date: LocalDate? = null): JsonNode? =
        get("/api/v1/life-map/warnings?userId=$userId" + dateParam(date))

    fun fetchExplanation(warningId: String): JsonNode? =
        get("/api/v1/life-map/recommendations/$warningId/explanation")

    private fun dateParam(date: LocalDate?): String =
        if (date == null) "" else "&date=$date"

    private fun get(path: String): JsonNode? {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.debug("life-map HTTP {} for {}", resp.code, path)
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                mapper.readTree(body)
            }
        }.getOrElse {
            log.debug("life-map call failed {}: {}", path, it.message)
            null
        }
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
