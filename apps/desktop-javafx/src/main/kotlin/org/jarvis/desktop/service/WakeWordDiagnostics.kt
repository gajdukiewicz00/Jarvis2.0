package org.jarvis.desktop.service

/**
 * Immutable snapshot of everything relevant to wake-word startup: runtime
 * environment, access-key presence + validation verdict, custom-model
 * compatibility, the input devices Porcupine can actually use (plus the ones
 * rejected as playback/output), every (device → success/failed) attempt, and the
 * last native failure (if any).
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
    val recommendedFix: String?,
    // ── section-5 fields ────────────────────────────────────────────────────
    /** Whether the key passed the cheap format check (length / not a placeholder). */
    val accessKeyLooksValidFormat: Boolean = false,
    /** "VALID" | "INVALID" | "UNKNOWN" — the engine-build validation verdict. */
    val accessKeyValidationStatus: String = "UNKNOWN",
    /** Redacted reason for an INVALID/UNKNOWN key verdict (never the key value). */
    val accessKeyFailureReason: String? = null,
    /** Raw system default input device BEFORE playback/output filtering. */
    val selectedInputDeviceBeforeFilter: String? = null,
    /** The microphone actually selected AFTER filtering (== selectedInputDevice). */
    val selectedInputDeviceAfterFilter: String? = null,
    /** Devices dropped from selection (e.g. "alsa_playback.java [default]"), with why. */
    val rejectedDevices: List<RejectedInputDevice> = emptyList(),
    /** Every (device → success/failed) init attempt, in order. */
    val triedInputDevices: List<WakeWordInitializer.TriedInputDevice> = emptyList(),
    /** "OK" | "INCOMPATIBLE" | "MISSING" | "FAILED". */
    val customModelStatus: String = "MISSING",
    /** "OK" | "FAILED" | "UNKNOWN". */
    val builtInJarvisStatus: String = "UNKNOWN",
    /** Always true here — wake-word failing never disables Manual Talk. */
    val manualTalkStillAvailable: Boolean = true
) {

    /** Full, compact diagnostics for the LOG. Never includes the key value. */
    fun toJson(): String = jsonObject(
        "porcupineVersion" to porcupineVersion,
        "osName" to osName,
        "osArch" to osArch,
        "javaVersion" to javaVersion,
        "accessKeyPresent" to accessKeyPresent,
        "accessKeyLooksValidFormat" to accessKeyLooksValidFormat,
        "accessKeyValidationStatus" to accessKeyValidationStatus,
        "accessKeyFailureReason" to accessKeyFailureReason,
        "mode" to mode,
        "customModelPath" to customModelPath,
        "customModelExists" to customModelExists,
        "customModelSizeBytes" to customModelSizeBytes,
        "customModelReadable" to customModelReadable,
        "customModelCompatible" to customModelCompatible,
        "customModelStatus" to customModelStatus,
        "builtInJarvisAvailable" to builtInJarvisAvailable,
        "builtInJarvisStatus" to builtInJarvisStatus,
        "keywordPathsCount" to keywordPathsCount,
        "sensitivitiesCount" to sensitivitiesCount,
        "availableInputDevices" to availableInputDevices,
        "defaultInputDevice" to defaultInputDevice,
        "selectedInputDevice" to selectedInputDevice,
        "selectedInputDeviceBeforeFilter" to selectedInputDeviceBeforeFilter,
        "selectedInputDeviceAfterFilter" to selectedInputDeviceAfterFilter,
        "manualTalkInputDevice" to manualTalkInputDevice,
        "rejectedDevices" to rejectedDevicesJson(),
        "triedInputDevices" to triedInputDevicesJson(),
        "manualTalkStillAvailable" to manualTalkStillAvailable,
        "lastInitError" to lastInitError,
        "nativeErrorCodes" to nativeErrorCodes,
        "recommendedFix" to recommendedFix
    )

    /**
     * The section-5 shape for the "Test Wake Word Setup" action. Never includes
     * the access-key value — only presence, format-check, and validation verdict.
     */
    fun testWakeWordSetupJson(): String = jsonObject(
        "accessKeyPresent" to accessKeyPresent,
        "accessKeyLooksValidFormat" to accessKeyLooksValidFormat,
        "accessKeyValidationStatus" to accessKeyValidationStatus,
        "accessKeyFailureReason" to accessKeyFailureReason,
        "customModelPresent" to customModelExists,
        "customModelCompatible" to customModelCompatible,
        "customModelStatus" to customModelStatus,
        "builtInJarvisAvailable" to builtInJarvisAvailable,
        "builtInJarvisStatus" to builtInJarvisStatus,
        "availableInputDevices" to availableInputDevices,
        "selectedInputDevice" to selectedInputDevice,
        "selectedInputDeviceBeforeFilter" to selectedInputDeviceBeforeFilter,
        "selectedInputDeviceAfterFilter" to selectedInputDeviceAfterFilter,
        "manualTalkInputDevice" to manualTalkInputDevice,
        "rejectedDevices" to rejectedDevicesJson(),
        "triedInputDevices" to triedInputDevicesJson(),
        "manualTalkStillAvailable" to manualTalkStillAvailable,
        "lastInitError" to lastInitError,
        "recommendedFix" to recommendedFix
    )

    private fun rejectedDevicesJson(): List<Map<String, Any?>> =
        rejectedDevices.map { mapOf("name" to it.name, "reason" to it.reason) }

    private fun triedInputDevicesJson(): List<Map<String, Any?>> =
        triedInputDevices.map { mapOf("name" to it.name, "status" to it.status, "error" to it.error) }
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
    is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
        "${jsonString(k.toString())}:${jsonValue(v)}"
    }
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
