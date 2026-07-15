package org.jarvis.desktop.service.wake

/**
 * Immutable diagnostics snapshot for one wake-word provider, aggregated by the
 * selector for the UI/logs. Dependency-free JSON emission (mirrors the existing
 * `WakeWordDiagnostics.toJson()` style) so it never drags in a serializer.
 *
 * @param providerId which provider this describes.
 * @param installed provider dependency present? (openWakeWord/vosk installed, or
 *   Porcupine key valid). null = unknown / not applicable.
 * @param reachable sidecar reachable? null = not a network provider.
 * @param models models the provider reports it can load.
 * @param listening currently streaming wake events.
 * @param lastWakeScore score of the most recent wake, if any.
 * @param lastWakeDetectedAt ISO timestamp of the most recent wake, if any.
 * @param lastError last error reason (redacted, never a key value).
 * @param paused detection is paused (mic/stream held off during a command/TTS)
 *   WITHOUT the provider being torn down. Defaults to false.
 * @param extra provider-specific key/values (engine name, sidecar url, ...).
 */
data class WakeProviderDiagnostics(
    val providerId: String,
    val installed: Boolean?,
    val reachable: Boolean?,
    val models: List<String>,
    val listening: Boolean,
    val lastWakeScore: Double?,
    val lastWakeDetectedAt: String?,
    val lastError: String?,
    val paused: Boolean = false,
    val extra: Map<String, String> = emptyMap()
) {
    fun toJson(): String = wakeJsonObject(
        "providerId" to providerId,
        "installed" to installed,
        "reachable" to reachable,
        "models" to models,
        "listening" to listening,
        "lastWakeScore" to lastWakeScore,
        "lastWakeDetectedAt" to lastWakeDetectedAt,
        "lastError" to lastError,
        "paused" to paused,
        "extra" to extra
    )
}

// ── minimal, dependency-free JSON emission (file-private to the wake package) ──

private fun wakeJsonObject(vararg pairs: Pair<String, Any?>): String =
    pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
        "${wakeJsonString(key)}:${wakeJsonValue(value)}"
    }

private fun wakeJsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Int -> value.toString()
    is Long -> value.toString()
    is Double -> value.toString()
    is String -> wakeJsonString(value)
    is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
        "${wakeJsonString(k.toString())}:${wakeJsonValue(v)}"
    }
    is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { wakeJsonValue(it) }
    else -> wakeJsonString(value.toString())
}

private fun wakeJsonString(raw: String): String {
    val sb = StringBuilder(raw.length + 2)
    sb.append('"')
    for (c in raw) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
