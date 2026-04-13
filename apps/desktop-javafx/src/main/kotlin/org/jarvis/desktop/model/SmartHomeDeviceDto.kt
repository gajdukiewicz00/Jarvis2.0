package org.jarvis.desktop.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartHomeDeviceDto(
    val id: String = "",
    val displayName: String = "",
    val room: String = "",
    val type: String = "",
    val supportedActions: List<String> = emptyList(),
    val state: Map<String, Any?> = emptyMap(),
    val provider: String = "",
    val updatedAt: String = ""
)

data class SmartHomeActionCommand(
    val action: String,
    val payload: String? = null
)
