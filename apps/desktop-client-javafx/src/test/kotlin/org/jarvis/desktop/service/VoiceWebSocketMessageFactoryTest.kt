package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoiceWebSocketMessageFactoryTest {

    @Test
    fun configMessageUsesServerContract() {
        val payload = Json.parseToJsonElement(
            VoiceWebSocketMessageFactory().configMessage(mapOf("language" to "ru-RU"))
        ).jsonObject

        assertEquals("CONFIG", payload["type"]?.jsonPrimitive?.content)
        assertEquals("ru-RU", payload["config"]?.jsonObject?.get("language")?.jsonPrimitive?.content)
    }

    @Test
    fun startMessageCarriesExplicitRecognitionLanguage() {
        val payload = Json.parseToJsonElement(
            VoiceWebSocketMessageFactory().startMessage("corr-1", "ru-RU")
        ).jsonObject

        assertEquals("START", payload["type"]?.jsonPrimitive?.content)
        assertEquals("corr-1", payload["correlationId"]?.jsonPrimitive?.content)
        assertEquals("ru-RU", payload["language"]?.jsonPrimitive?.content)
    }
}
