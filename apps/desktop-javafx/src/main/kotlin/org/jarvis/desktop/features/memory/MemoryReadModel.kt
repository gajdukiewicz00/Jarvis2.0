package org.jarvis.desktop.features.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Memory panel.
 *
 * Wires:
 *  - unified search  -> POST   /api/v1/memory/search/unified
 *  - recent notes    -> GET    /api/v1/memory/notes?scope=&limit=
 *  - edit note       -> PUT    /api/v1/memory/notes/{memoryId}
 *  - forget note      -> DELETE /api/v1/memory/notes/{memoryId}?actor=&reason=
 *  - forget by query -> DELETE /api/v1/memory/notes/by-query?query=&scope=&actor=&reason=
 *  - why remembered  -> GET    /api/v1/memory/notes/{memoryId}/why
 *  - pin/unpin note  -> PUT/DELETE /api/v1/memory/notes/{memoryId}/pin
 *  - change scope    -> PUT    /api/v1/memory/notes/{memoryId}/scope?scope=
 *  - encrypted export -> GET   /api/v1/memory/notes/export/encrypted-or-plain?scope=
 *  - conflict-aware import -> POST /api/v1/memory/notes/import/resolve?mode=
 *                              (or /import/encrypted/resolve?mode= for an encrypted envelope)
 *
 * Endpoints are relative to the desktop client's `/api/v1` base URL. Result
 * shapes vary by deployment, so parsing probes several common container keys
 * (`results`, `notes`, `items`, `data`) and falls back to an empty list.
 */
class MemoryReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    /** Mirrors `org.jarvis.memory.obsidian.MemoryScope` on the memory-service side. */
    companion object {
        val SCOPES: List<String> = listOf("USER_PROFILE", "PROJECT", "SESSION", "FINANCE", "HEALTH", "TEMPORARY")

        /** Mirrors `org.jarvis.memory.obsidian.ImportConflictMode` on the memory-service side. */
        val IMPORT_CONFLICT_MODES: List<String> = listOf("skip", "overwrite", "keep-both")

        /** Mirrors `org.jarvis.memory.obsidian.MemoryNoteRequest`'s fields — see [sanitizeNoteArray]. */
        private val NOTE_REQUEST_FIELDS: Set<String> = setOf(
            "memoryId", "category", "title", "summary", "body", "source", "privacy",
            "confidence", "tags", "linkedEntities", "scope", "expiresAt", "ttlSeconds"
        )
    }

    data class MemoryItem(
        val memoryId: String?,
        val title: String,
        val snippet: String,
        val source: String,
        val score: Double?,
        val pinned: Boolean = false,
        val scope: String? = null
    ) {
        /**
         * Edit/forget only apply to real memory notes. Unified search also
         * returns conversation chunks (`source == "conversation"`) whose
         * `id` is a chunk id, not a `/memory/notes/{memoryId}` key.
         */
        val isManageable: Boolean
            get() = !memoryId.isNullOrBlank() && source != "conversation"
    }

    /** `GET /{memoryId}/why` — provenance + privacy for "why does Jarvis remember this?". */
    data class WhyInfo(
        val memoryId: String,
        val source: String?,
        val confidence: Double?,
        val scope: String?,
        val privacy: String?,
        val pinned: Boolean,
        val createdAt: String?,
        val explanation: String?
    )

    fun search(query: String, limit: Int = 10): List<MemoryItem> {
        val payload = objectMapper.createObjectNode().apply {
            put("query", query.trim())
            put("limit", limit)
        }
        val response = apiClient.post("/memory/search/unified", objectMapper.writeValueAsString(payload))
        return parseItems(objectMapper.readTree(response))
    }

    /** [scope] filters to one of [SCOPES] (e.g. "FINANCE"); null/blank means all scopes. */
    fun recentNotes(limit: Int = 15, scope: String? = null): List<MemoryItem> {
        val query = buildString {
            append("/memory/notes?limit=").append(limit)
            scope?.trim()?.takeIf { it.isNotEmpty() }?.let { append("&scope=").append(encode(it)) }
        }
        val response = apiClient.get(query)
        return parseItems(objectMapper.readTree(response))
    }

    /** Full note detail for editing — list snippets are truncated to 500 chars. */
    data class NoteDetail(val memoryId: String, val title: String, val body: String)

    fun getNote(memoryId: String): NoteDetail {
        val response = apiClient.get("/memory/notes/${encode(memoryId)}")
        val node = objectMapper.readTree(response)
        return NoteDetail(
            memoryId = firstNonBlank(node.path("memoryId").textOrNull(), node.path("id").textOrNull())
                ?: memoryId,
            title = node.path("title").textOrNull() ?: "",
            body = firstNonBlank(node.path("body").textOrNull(), node.path("summary").textOrNull()) ?: ""
        )
    }

    /** Partial update — only [title]/[body] are sent, so untouched fields survive. */
    fun updateNote(memoryId: String, title: String, body: String) {
        val payload = objectMapper.createObjectNode().apply {
            put("title", title.trim())
            put("body", body)
        }
        apiClient.put("/memory/notes/${encode(memoryId)}", objectMapper.writeValueAsString(payload))
    }

    /** "Jarvis, forget this" — soft-deletes the note. HIGH-risk, confirm before calling. */
    fun forgetNote(memoryId: String, actor: String?, reason: String?) {
        val query = buildString {
            append("/memory/notes/").append(encode(memoryId))
            val params = mutableListOf<String>()
            actor?.takeIf { it.isNotBlank() }?.let { params += "actor=${encode(it)}" }
            reason?.takeIf { it.isNotBlank() }?.let { params += "reason=${encode(it)}" }
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        apiClient.delete(query)
    }

    /** "Why does Jarvis remember this?" — source, confidence, scope, privacy and pin state. */
    fun why(memoryId: String): WhyInfo {
        val node = objectMapper.readTree(apiClient.get("/memory/notes/${encode(memoryId)}/why"))
        return WhyInfo(
            memoryId = node.path("memoryId").textOrNull() ?: memoryId,
            source = node.path("source").textOrNull(),
            confidence = node.path("confidence").let { if (it.isNumber) it.asDouble() else null },
            scope = node.path("scope").textOrNull(),
            privacy = node.path("privacy").textOrNull(),
            pinned = node.path("pinned").let { it.isBoolean && it.asBoolean() },
            createdAt = node.path("createdAt").textOrNull(),
            explanation = node.path("explanation").textOrNull()
        )
    }

    /** Roadmap #11 — mark pinned: excluded from TTL cleanup, ranked higher in search. */
    fun pinNote(memoryId: String) {
        apiClient.put("/memory/notes/${encode(memoryId)}/pin", "{}")
    }

    /** Roadmap #11 — unmark pinned. */
    fun unpinNote(memoryId: String) {
        apiClient.delete("/memory/notes/${encode(memoryId)}/pin")
    }

    /** Roadmap #11 — dedicated "change scope" op; re-applies the finance/health privacy guard server-side. */
    fun changeScope(memoryId: String, scope: String) {
        apiClient.put("/memory/notes/${encode(memoryId)}/scope?scope=${encode(scope)}", "{}")
    }

    /** Outcome of a conflict-aware bulk import (mirrors `MemoryExportService.ConflictImportSummary`). */
    data class ImportResult(
        val received: Int,
        val created: Int,
        val overwritten: Int,
        val skipped: Int,
        val failed: Int,
        val errors: List<String>
    )

    /** Result of a "forget by query" sweep (mirrors `MemoryForgetService.ForgetByQueryResult`). */
    data class ForgetByQueryResult(val count: Int, val memoryIds: List<String>)

    /** Outcome of a client-side bulk "forget" loop (no batch-by-id endpoint exists server-side). */
    data class BulkForgetResult(val requested: Int, val forgotten: List<String>, val failed: List<String>)

    /**
     * Bulk takeout for the export button: AES-256/GCM-encrypted when the
     * memory-service has an encryption key configured, otherwise the plain
     * takeout with an explicit `encrypted:false` flag — never fails just
     * because no key is configured. Returns the raw JSON body so the caller
     * can write it to disk unchanged (round-trips through [importFile]).
     */
    fun exportEncryptedOrPlain(scope: String? = null): String {
        val endpoint = buildString {
            append("/memory/notes/export/encrypted-or-plain")
            scope?.trim()?.takeIf { it.isNotEmpty() }?.let { append("?scope=").append(encode(it)) }
        }
        return apiClient.get(endpoint)
    }

    /**
     * Bulk import from a file previously produced by [exportEncryptedOrPlain] (or the raw
     * `/export`, `/export/encrypted` responses). Detects the payload shape and posts to the
     * matching conflict-aware endpoint:
     *  - a bare JSON array -> plaintext notes -> `POST /import/resolve?mode=`
     *  - `{algorithm, ivBase64, ciphertextBase64}` -> an encrypted envelope -> `POST /import/encrypted/resolve?mode=`
     *  - `{encrypted, envelope, notes}` (the [exportEncryptedOrPlain] wrapper) -> unwrapped and routed to
     *    whichever of the two above applies.
     *
     * [mode] is one of [IMPORT_CONFLICT_MODES] ("skip" / "overwrite" / "keep-both").
     */
    fun importFile(content: String, mode: String): ImportResult {
        val node = objectMapper.readTree(content)
        val modeParam = encode(mode)
        return when {
            node.isArray ->
                postImportResolve("/memory/notes/import/resolve?mode=$modeParam", sanitizeNoteArray(node))
            node.has("ciphertextBase64") ->
                postImportResolve("/memory/notes/import/encrypted/resolve?mode=$modeParam", node)
            node.has("envelope") || node.has("notes") -> {
                val isEncrypted = node.path("encrypted").let { it.isBoolean && it.asBoolean() }
                val envelope = node.path("envelope")
                if (isEncrypted && !envelope.isMissingNode && !envelope.isNull) {
                    postImportResolve("/memory/notes/import/encrypted/resolve?mode=$modeParam", envelope)
                } else {
                    val notes = node.path("notes").takeIf { it.isArray } ?: objectMapper.createArrayNode()
                    postImportResolve("/memory/notes/import/resolve?mode=$modeParam", sanitizeNoteArray(notes))
                }
            }
            else -> throw IllegalArgumentException("Unrecognized memory export file format.")
        }
    }

    /**
     * `/export` (plaintext) and the plaintext branch of `/export/encrypted-or-plain` both serialize the
     * full `MemoryNoteEntity` read-model (extra DB-only fields like `status`/`contentHash`/`embedding`
     * alongside the write-model fields). Unlike the encrypted-import path (which the memory-service
     * decodes with a lenient, unknown-properties-tolerant reader), `/import/resolve` binds straight to
     * `MemoryNoteRequest` with no such tolerance — so a straight export-then-import round trip of a
     * plaintext takeout would otherwise 400. Trim each entry down to just the `MemoryNoteRequest` fields
     * before posting.
     */
    private fun sanitizeNoteArray(notes: JsonNode): JsonNode {
        if (!notes.isArray) return notes
        val sanitized = objectMapper.createArrayNode()
        notes.forEach { note ->
            if (!note.isObject) {
                sanitized.add(note)
                return@forEach
            }
            val clean = objectMapper.createObjectNode()
            NOTE_REQUEST_FIELDS.forEach { field ->
                if (note.has(field)) clean.replace(field, note.path(field))
            }
            sanitized.add(clean)
        }
        return sanitized
    }

    private fun postImportResolve(endpoint: String, body: JsonNode): ImportResult {
        val response = apiClient.post(endpoint, objectMapper.writeValueAsString(body))
        val node = objectMapper.readTree(response)
        return ImportResult(
            received = node.path("received").asInt(0),
            created = node.path("created").asInt(0),
            overwritten = node.path("overwritten").asInt(0),
            skipped = node.path("skipped").asInt(0),
            failed = node.path("failed").asInt(0),
            errors = node.path("errors").takeIf { it.isArray }?.map { it.asText() } ?: emptyList()
        )
    }

    /**
     * Roadmap #11 — "forget by query": soft-deletes every ACTIVE note matching a text query
     * and/or scope filter in one call. Backs the "forget by query" input in the Memory panel.
     */
    fun forgetByQuery(query: String?, scope: String?, actor: String?, reason: String?): ForgetByQueryResult {
        val endpoint = buildString {
            append("/memory/notes/by-query")
            val params = mutableListOf<String>()
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "query=${encode(it)}" }
            scope?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "scope=${encode(it)}" }
            actor?.takeIf { it.isNotBlank() }?.let { params += "actor=${encode(it)}" }
            reason?.takeIf { it.isNotBlank() }?.let { params += "reason=${encode(it)}" }
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        val node = objectMapper.readTree(apiClient.delete(endpoint))
        return ForgetByQueryResult(
            count = node.path("count").asInt(0),
            memoryIds = node.path("memoryIds").takeIf { it.isArray }?.map { it.asText() } ?: emptyList()
        )
    }

    /**
     * Bulk "forget" for multi-select delete. There is no batch-by-id endpoint server-side, so this
     * loops [forgetNote] per id; one failure does not abort the rest of the batch.
     */
    fun forgetMany(memoryIds: List<String>, actor: String?, reason: String?): BulkForgetResult {
        val forgotten = mutableListOf<String>()
        val failed = mutableListOf<String>()
        memoryIds.forEach { memoryId ->
            try {
                forgetNote(memoryId, actor, reason)
                forgotten += memoryId
            } catch (e: Exception) {
                failed += memoryId
            }
        }
        return BulkForgetResult(memoryIds.size, forgotten, failed)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun parseItems(root: JsonNode): List<MemoryItem> {
        val container = when {
            root.isArray -> root
            root.path("results").isArray -> root.path("results")
            root.path("notes").isArray -> root.path("notes")
            root.path("items").isArray -> root.path("items")
            root.path("data").isArray -> root.path("data")
            else -> return emptyList()
        }
        return container.map(::parseItem)
    }

    private fun parseItem(node: JsonNode): MemoryItem {
        val memoryId = firstNonBlank(
            node.path("memoryId").textOrNull(),
            node.path("id").textOrNull()
        )
        val title = firstNonBlank(
            node.path("title").textOrNull(),
            node.path("name").textOrNull(),
            node.path("path").textOrNull(),
            node.path("id").textOrNull()
        ) ?: "Untitled note"
        val snippet = firstNonBlank(
            node.path("snippet").textOrNull(),
            node.path("content").textOrNull(),
            node.path("text").textOrNull(),
            node.path("body").textOrNull(),
            node.path("summary").textOrNull()
        ) ?: ""
        val source = firstNonBlank(
            node.path("source").textOrNull(),
            node.path("type").textOrNull(),
            node.path("kind").textOrNull()
        ) ?: "memory"
        val score = node.path("score").let { if (it.isNumber) it.asDouble() else null }
            ?: node.path("similarity").let { if (it.isNumber) it.asDouble() else null }
        val pinned = node.path("pinned").let { it.isBoolean && it.asBoolean() }
        val scope = node.path("scope").textOrNull()
        return MemoryItem(memoryId, title, snippet.take(500), source, score, pinned, scope)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
