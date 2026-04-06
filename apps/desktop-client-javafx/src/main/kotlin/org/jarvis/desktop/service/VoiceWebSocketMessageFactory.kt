package org.jarvis.desktop.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class VoiceWebSocketMessageFactory {
    fun configMessage(config: Map<String, String>): String {
        val normalizedConfig = config
            .mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }

        return buildJsonObject {
            put("type", "CONFIG")
            put("config", buildJsonObject {
                normalizedConfig.forEach { (key, value) ->
                    put(key, value)
                }
            })
        }.toString()
    }

    fun startMessage(correlationId: String, language: String): String {
        return buildJsonObject {
            put("type", "START")
            put("correlationId", correlationId)
            put("language", language.trim())
        }.toString()
    }
}
