package org.jarvis.desktop.service

/**
 * Immutable snapshot of everything relevant to wake-word startup: runtime
 * environment, access-key presence, custom-model compatibility, the input
 * devices Porcupine can actually use, and the last native failure (if any).
 *
 * The raw native error stack lives here so it can be logged, but the
 * user-facing [testWakeWordSetupJson] deliberately omits low-level noise and
 * NEVER contains the Porcupine access key value.
 */
data class WakeWordDiagnostics(
    val porcupineVersion: String,
    val osName: String,
    val osArch: String,
    val javaVersion: String,
    val accessKeyPresent: Boolean,
    /** One of CUSTOM_RU, BUILTIN_JARVIS, NONE. */
    val mode: String,
    val customModelPath: String?,
    val customModelExists: Boolean,
    val customModelSizeBytes: Long,
    val customModelReadable: Boolean,
    /** null = not tried; true/false = tried and (in)compatible. */
    val customModelCompatible: Boolean?,
    /** null = not reached; true/false = built-in probe result. */
    val builtInJarvisAvailable: Boolean?,
    val keywordPathsCount: Int,
    val sensitivitiesCount: Int,
    val availableInputDevices: List<String>,
    val defaultInputDevice: String?,
    val selectedInputDevice: String?,
    val manualTalkInputDevice: String?,
    val lastInitError: String?,
    val nativeErrorCodes: List<String>,
    val recommendedFix: String?
) {

    /** Full, compact diagnostics for the LOG. */
    fun toJson(): String = jsonObject(
        "porcupineVersion" to porcupineVersion,
        "osName" to osName,
        "osArch" to osArch,
        "javaVersion" to javaVersion,
        "accessKeyPresent" to accessKeyPresent,
        "mode" to mode,
        "customModelPath" to customModelPath,
        "customModelExists" to customModelExists,
        "customModelSizeBytes" to customModelSizeBytes,
        "customModelReadable" to customModelReadable,
        "customModelCompatible" to customModelCompatible,
        "builtInJarvisAvailable" to builtInJarvisAvailable,
        "keywordPathsCount" to keywordPathsCount,
        "sensitivitiesCount" to sensitivitiesCount,
        "availableInputDevices" to availableInputDevices,
        "defaultInputDevice" to defaultInputDevice,
        "selectedInputDevice" to selectedInputDevice,
        "manualTalkInputDevice" to manualTalkInputDevice,
        "lastInitError" to lastInitError,
        "nativeErrorCodes" to nativeErrorCodes,
        "recommendedFix" to recommendedFix
    )

    /**
     * The exact section-6 shape for the "Test Wake Word Setup" action. Never
     * includes the access-key value — only whether one is present.
     */
    fun testWakeWordSetupJson(): String = jsonObject(
        "accessKeyPresent" to accessKeyPresent,
        "customModelPresent" to customModelExists,
        "customModelCompatible" to customModelCompatible,
        "builtInJarvisAvailable" to builtInJarvisAvailable,
        "availableInputDevices" to availableInputDevices,
        "selectedInputDevice" to selectedInputDevice,
        "manualTalkInputDevice" to manualTalkInputDevice,
        "lastInitError" to lastInitError,
        "recommendedFix" to recommendedFix
    )
}

// ── minimal, dependency-free JSON emission ──────────────────────────────────

private fun jsonObject(vararg pairs: Pair<String, Any?>): String =
    pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
        "${jsonString(key)}:${jsonValue(value)}"
    }

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Int -> value.toString()
    is Long -> value.toString()
    is String -> jsonString(value)
    is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonValue(it) }
    else -> jsonString(value.toString())
}

private fun jsonString(raw: String): String {
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
