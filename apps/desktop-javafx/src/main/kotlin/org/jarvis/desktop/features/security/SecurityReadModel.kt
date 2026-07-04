package org.jarvis.desktop.features.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Security / Privacy panel.
 *
 * Wires the privacy toggle surface:
 *  - status -> GET  /api/v1/security/auth/privacy
 *  - enable -> POST /api/v1/security/auth/privacy/on
 *  - disable-> POST /api/v1/security/auth/privacy/off
 */
class SecurityReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class PrivacySnapshot(
        val enabled: Boolean,
        val detail: String
    )

    fun status(): PrivacySnapshot = parse(apiClient.get("/security/auth/privacy"))

    fun enablePrivacy(): PrivacySnapshot = parse(apiClient.post("/security/auth/privacy/on", "{}"))

    fun disablePrivacy(): PrivacySnapshot = parse(apiClient.post("/security/auth/privacy/off", "{}"))

    private fun parse(body: String): PrivacySnapshot {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return PrivacySnapshot(false, body.trim())
        val enabled = firstBoolean(
            root.path("privacyEnabled"),
            root.path("enabled"),
            root.path("privacy"),
            root.path("active")
        ) ?: false
        val detail = firstNonBlank(
            root.path("detail").textOrNull(),
            root.path("message").textOrNull(),
            root.path("status").textOrNull(),
            root.path("reason").textOrNull()
        ) ?: if (enabled) "Privacy mode is ON." else "Privacy mode is OFF."
        return PrivacySnapshot(enabled, detail)
    }

    private fun firstBoolean(vararg nodes: JsonNode): Boolean? =
        nodes.firstOrNull { it.isBoolean }?.asBoolean()

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
