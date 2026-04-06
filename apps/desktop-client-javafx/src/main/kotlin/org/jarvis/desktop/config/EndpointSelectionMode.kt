package org.jarvis.desktop.config

enum class EndpointSelectionMode {
    AUTO,
    MANUAL;

    companion object {
        fun fromPersisted(value: String?): EndpointSelectionMode? {
            return when (value?.trim()?.uppercase()) {
                AUTO.name -> AUTO
                MANUAL.name -> MANUAL
                else -> null
            }
        }
    }
}
