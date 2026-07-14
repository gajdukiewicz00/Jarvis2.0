package org.jarvis.desktop.service

/** The two wake-word engines, in preference order. */
enum class WakeWordMode { CUSTOM_RU, BUILTIN_JARVIS }

/** Outcome of validating the Porcupine access key WITHOUT opening a microphone. */
enum class AccessKeyValidation { VALID, INVALID, UNKNOWN }

/** Result of an access-key validation probe: the verdict and a redacted reason. */
data class AccessKeyValidationResult(val status: AccessKeyValidation, val reason: String?)

/** Stat of the bundled custom `.ppn` resource (null when the resource is absent). */
data class CustomModelInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val readable: Boolean
)

/** A single (mode, device) initialization attempt requested from the seam. */
data class WakeWordAttempt(val mode: WakeWordMode, val device: WakeWordInputDevice?)

/** Result of one attempt: an opaque started handle, or a captured failure. */
sealed interface WakeWordAttemptResult {
    data class Success(val handle: Any) : WakeWordAttemptResult
    data class Failure(
        val exceptionClass: String,
        val message: String?,
        val messageStack: List<String>,
        val nativeCode: String?
    ) : WakeWordAttemptResult
}

/** Environment/context that is constant for one initialization pass. */
data class StaticInitInfo(
    val porcupineVersion: String,
    val osName: String,
    val osArch: String,
    val javaVersion: String,
    val manualTalkDevice: String?,
    val defaultDevice: String?
)

/** Terminal outcome of [WakeWordInitializer.initialize]. */
sealed interface WakeWordInitOutcome {
    data class Enabled(
        val handle: Any,
        val mode: WakeWordMode,
        val device: WakeWordInputDevice?,
        val diagnostics: WakeWordDiagnostics
    ) : WakeWordInitOutcome

    data class Disabled(
        val reason: String,
        val userMessage: String,
        val diagnostics: WakeWordDiagnostics
    ) : WakeWordInitOutcome
}

/**
 * Pure orchestrator for wake-word startup. It contains NO native/hardware calls
 * of its own: every attempt to build+start an engine, the access-key validation,
 * and any cleanup of a probe handle, is delegated to injected seams. This makes
 * the whole custom→built-in fallback decision tree unit-testable with fakes.
 *
 * KEY INSIGHT: building a Porcupine engine validates the access key and needs NO
 * microphone. So [validateAccessKey] is checked SEPARATELY from opening a mic —
 * that lets us distinguish "key invalid" from "no real mic", and attribute an
 * all-attempts-failed outcome correctly (a VALID key means the failures are at
 * mic-open, not at the key).
 *
 * Order of [initialize]:
 *  1. no access key             → Disabled("access_key_missing")
 *  2. key fails a cheap format check → Disabled("access_key_invalid")
 *  3. [validateAccessKey] == INVALID → Disabled("access_key_invalid")
 *  4. no compatible mic         → Disabled("no_input_device")
 *  5. custom model (if present & readable) on each device; first Success →
 *     Enabled(CUSTOM_RU). All fail → mark incompatible, fall through.
 *  6. built-in Jarvis on each device; first Success → Enabled(BUILTIN_JARVIS).
 *  7. everything failed → Disabled: attributed to the mic when the key is VALID
 *     ("no_working_microphone"), otherwise to the model/key ("all_failed").
 */
class WakeWordInitializer(
    private val accessKeyPresent: Boolean,
    private val customModel: CustomModelInfo?,
    private val devices: List<WakeWordInputDevice>,
    private val attempt: (WakeWordAttempt) -> WakeWordAttemptResult,
    private val staticInfo: StaticInitInfo,
    private val persistDevice: (WakeWordInputDevice) -> Unit = {},
    // Extra seam (not in the base spec): diagnose() opens real handles to probe
    // compatibility; this releases any Success handle so the mic is not left open.
    private val stopProbe: (Any) -> Unit = {},
    // ── appended seams (section 2) ──────────────────────────────────────────
    private val accessKeyLooksValidFormat: Boolean = true,
    private val rejectedDevices: List<RejectedInputDevice> = emptyList(),
    private val selectedDeviceBeforeFilter: String? = null,
    private val validateAccessKey: () -> AccessKeyValidationResult = {
        AccessKeyValidationResult(AccessKeyValidation.UNKNOWN, null)
    }
) {

    /** A single (device, result) initialization attempt, recorded for diagnostics. */
    data class TriedInputDevice(val name: String, val status: String, val error: String?)

    fun initialize(): WakeWordInitOutcome {
        if (!accessKeyPresent) {
            return disabled(
                reason = "access_key_missing",
                userMessage = NO_KEY_MESSAGE,
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null,
                validationStatus = AccessKeyValidation.UNKNOWN,
                kvReason = null,
                tried = emptyList()
            )
        }

        if (!accessKeyLooksValidFormat) {
            return disabled(
                reason = "access_key_invalid",
                userMessage = INVALID_KEY_MESSAGE,
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null,
                validationStatus = AccessKeyValidation.INVALID,
                kvReason = "blank/placeholder/too short",
                tried = emptyList()
            )
        }

        val kv = validateAccessKey()
        if (kv.status == AccessKeyValidation.INVALID) {
            return disabled(
                reason = "access_key_invalid",
                userMessage = INVALID_KEY_MESSAGE,
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null,
                validationStatus = AccessKeyValidation.INVALID,
                kvReason = kv.reason,
                tried = emptyList()
            )
        }

        if (devices.isEmpty()) {
            return disabled(
                reason = "no_input_device",
                userMessage = NO_MIC_MESSAGE,
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null,
                validationStatus = kv.status,
                kvReason = kv.reason,
                tried = emptyList()
            )
        }

        val tried = mutableListOf<TriedInputDevice>()
        var customTried = false
        var lastFailure: WakeWordAttemptResult.Failure? = null

        if (customModel != null && customModel.exists && customModel.readable) {
            customTried = true
            for (device in devices) {
                when (val result = attempt(WakeWordAttempt(WakeWordMode.CUSTOM_RU, device))) {
                    is WakeWordAttemptResult.Success -> {
                        tried += TriedInputDevice(device.name, "success", null)
                        persistDevice(device)
                        return enabled(
                            handle = result.handle,
                            mode = WakeWordMode.CUSTOM_RU,
                            device = device,
                            customCompatible = true,
                            builtInAvailable = null,
                            lastFailure = null,
                            validationStatus = kv.status,
                            kvReason = kv.reason,
                            tried = tried.toList()
                        )
                    }
                    is WakeWordAttemptResult.Failure -> {
                        tried += TriedInputDevice(device.name, "failed", formatError(result))
                        lastFailure = result
                    }
                }
            }
        }

        for (device in devices) {
            when (val result = attempt(WakeWordAttempt(WakeWordMode.BUILTIN_JARVIS, device))) {
                is WakeWordAttemptResult.Success -> {
                    tried += TriedInputDevice(device.name, "success", null)
                    persistDevice(device)
                    return enabled(
                        handle = result.handle,
                        mode = WakeWordMode.BUILTIN_JARVIS,
                        device = device,
                        customCompatible = if (customTried) false else null,
                        builtInAvailable = true,
                        lastFailure = lastFailure,
                        validationStatus = kv.status,
                        kvReason = kv.reason,
                        tried = tried.toList()
                    )
                }
                is WakeWordAttemptResult.Failure -> {
                    tried += TriedInputDevice(device.name, "failed", formatError(result))
                    lastFailure = result
                }
            }
        }

        // Everything failed. Attribute the cause: a VALID key means the engine
        // builds, so the only remaining failure point is opening the microphone.
        val keyIsValid = kv.status == AccessKeyValidation.VALID
        val reason = if (keyIsValid) "no_working_microphone" else "all_failed"
        val userMessage = if (keyIsValid) {
            NO_MIC_MESSAGE
        } else {
            val detail = if (customTried) {
                "incompatible custom model and built-in Jarvis init failed"
            } else {
                "built-in Jarvis init failed"
            }
            "Wake word unavailable. Manual Talk still works. Reason: $detail."
        }
        return disabled(
            reason = reason,
            userMessage = userMessage,
            mode = "NONE",
            customCompatible = if (customTried) false else null,
            builtInAvailable = false,
            selectedDevice = devices.firstOrNull()?.name,
            lastFailure = lastFailure,
            validationStatus = kv.status,
            kvReason = kv.reason,
            tried = tried.toList()
        )
    }

    /**
     * Dry probe for "Test Wake Word Setup": validates the key, then attempts custom
     * then built-in on the preferred (first) device, recording compatibility. Any
     * Success handle is released via [stopProbe] so the microphone is never left open.
     */
    fun diagnose(): WakeWordDiagnostics {
        if (!accessKeyPresent) {
            return build("NONE", null, null, null, null, "access_key_missing", AccessKeyValidation.UNKNOWN, null, emptyList())
        }
        if (!accessKeyLooksValidFormat) {
            return build(
                "NONE", null, null, null, null, "access_key_invalid",
                AccessKeyValidation.INVALID, "blank/placeholder/too short", emptyList()
            )
        }
        val kv = validateAccessKey()
        if (kv.status == AccessKeyValidation.INVALID) {
            return build("NONE", null, null, null, null, "access_key_invalid", AccessKeyValidation.INVALID, kv.reason, emptyList())
        }
        val device = devices.firstOrNull()
            ?: return build("NONE", null, null, null, null, "no_input_device", kv.status, kv.reason, emptyList())

        val tried = mutableListOf<TriedInputDevice>()
        var customCompatible: Boolean? = null
        var builtInAvailable: Boolean? = null
        var lastFailure: WakeWordAttemptResult.Failure? = null

        if (customModel != null && customModel.exists && customModel.readable) {
            when (val r = attempt(WakeWordAttempt(WakeWordMode.CUSTOM_RU, device))) {
                is WakeWordAttemptResult.Success -> {
                    customCompatible = true
                    tried += TriedInputDevice(device.name, "success", null)
                    stopProbe(r.handle)
                }
                is WakeWordAttemptResult.Failure -> {
                    customCompatible = false
                    tried += TriedInputDevice(device.name, "failed", formatError(r))
                    lastFailure = r
                }
            }
        }

        when (val r = attempt(WakeWordAttempt(WakeWordMode.BUILTIN_JARVIS, device))) {
            is WakeWordAttemptResult.Success -> {
                builtInAvailable = true
                tried += TriedInputDevice(device.name, "success", null)
                stopProbe(r.handle)
            }
            is WakeWordAttemptResult.Failure -> {
                builtInAvailable = false
                tried += TriedInputDevice(device.name, "failed", formatError(r))
                lastFailure = r
            }
        }

        val mode = when {
            customCompatible == true -> "CUSTOM_RU"
            builtInAvailable == true -> "BUILTIN_JARVIS"
            else -> "NONE"
        }
        val reason = when {
            mode != "NONE" -> null
            kv.status == AccessKeyValidation.VALID -> "no_working_microphone"
            else -> "all_failed"
        }
        return build(mode, customCompatible, builtInAvailable, device.name, lastFailure, reason, kv.status, kv.reason, tried.toList())
    }

    // ── diagnostics assembly ────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun enabled(
        handle: Any,
        mode: WakeWordMode,
        device: WakeWordInputDevice?,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        lastFailure: WakeWordAttemptResult.Failure?,
        validationStatus: AccessKeyValidation,
        kvReason: String?,
        tried: List<TriedInputDevice>
    ): WakeWordInitOutcome.Enabled = WakeWordInitOutcome.Enabled(
        handle = handle,
        mode = mode,
        device = device,
        diagnostics = build(mode.name, customCompatible, builtInAvailable, device?.name, lastFailure, null, validationStatus, kvReason, tried)
    )

    @Suppress("LongParameterList")
    private fun disabled(
        reason: String,
        userMessage: String,
        mode: String,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        selectedDevice: String?,
        lastFailure: WakeWordAttemptResult.Failure?,
        validationStatus: AccessKeyValidation,
        kvReason: String?,
        tried: List<TriedInputDevice>
    ): WakeWordInitOutcome.Disabled = WakeWordInitOutcome.Disabled(
        reason = reason,
        userMessage = userMessage,
        diagnostics = build(mode, customCompatible, builtInAvailable, selectedDevice, lastFailure, reason, validationStatus, kvReason, tried)
    )

    @Suppress("LongParameterList")
    private fun build(
        mode: String,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        selectedDevice: String?,
        lastFailure: WakeWordAttemptResult.Failure?,
        reason: String?,
        validationStatus: AccessKeyValidation,
        kvReason: String?,
        tried: List<TriedInputDevice>
    ): WakeWordDiagnostics = WakeWordDiagnostics(
        porcupineVersion = staticInfo.porcupineVersion,
        osName = staticInfo.osName,
        osArch = staticInfo.osArch,
        javaVersion = staticInfo.javaVersion,
        accessKeyPresent = accessKeyPresent,
        mode = mode,
        customModelPath = customModel?.path,
        customModelExists = customModel?.exists ?: false,
        customModelSizeBytes = customModel?.sizeBytes ?: 0L,
        customModelReadable = customModel?.readable ?: false,
        customModelCompatible = customCompatible,
        builtInJarvisAvailable = builtInAvailable,
        keywordPathsCount = if (mode == WakeWordMode.CUSTOM_RU.name) 1 else 0,
        sensitivitiesCount = if (mode == "NONE") 0 else 1,
        availableInputDevices = devices.map { it.name },
        defaultInputDevice = staticInfo.defaultDevice,
        selectedInputDevice = selectedDevice,
        manualTalkInputDevice = staticInfo.manualTalkDevice,
        lastInitError = lastFailure?.let { formatError(it) },
        nativeErrorCodes = listOfNotNull(lastFailure?.nativeCode),
        recommendedFix = recommendedFix(customCompatible, builtInAvailable, validationStatus),
        accessKeyLooksValidFormat = accessKeyLooksValidFormat,
        accessKeyValidationStatus = validationStatus.name,
        accessKeyFailureReason = kvReason,
        selectedInputDeviceBeforeFilter = selectedDeviceBeforeFilter,
        selectedInputDeviceAfterFilter = selectedDevice,
        rejectedDevices = rejectedDevices,
        triedInputDevices = tried,
        customModelStatus = customModelStatus(customCompatible),
        builtInJarvisStatus = builtInJarvisStatus(builtInAvailable),
        manualTalkStillAvailable = true
    )

    private fun formatError(failure: WakeWordAttemptResult.Failure): String =
        failure.message?.takeIf { it.isNotBlank() }
            ?: failure.messageStack.firstOrNull()
            ?: failure.exceptionClass

    private fun customModelStatus(customCompatible: Boolean?): String = when {
        customModel == null || !customModel.exists || !customModel.readable -> "MISSING"
        customCompatible == true -> "OK"
        customCompatible == false -> "INCOMPATIBLE"
        else -> "MISSING"
    }

    private fun builtInJarvisStatus(builtInAvailable: Boolean?): String = when (builtInAvailable) {
        true -> "OK"
        false -> "FAILED"
        null -> "UNKNOWN"
    }

    private fun recommendedFix(
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        validationStatus: AccessKeyValidation
    ): String? = when {
        !accessKeyPresent -> "set PORCUPINE_ACCESS_KEY"
        !accessKeyLooksValidFormat -> "generate a new key in Picovoice Console and set PORCUPINE_ACCESS_KEY"
        validationStatus == AccessKeyValidation.INVALID ->
            "generate a new key in Picovoice Console and set PORCUPINE_ACCESS_KEY"
        devices.isEmpty() -> "choose another input device"
        validationStatus == AccessKeyValidation.VALID && builtInAvailable == false ->
            "select C4K or T1 as the wake-word microphone"
        builtInAvailable == false -> "check Porcupine access key validity"
        customCompatible == false -> "regenerate jarvis_ru.ppn for Porcupine 4.x or it will use built-in Jarvis"
        else -> null
    }

    companion object {
        const val NO_KEY_MESSAGE =
            "Wake word unavailable. Manual Talk still works. Reason: Porcupine access key not configured."
        const val INVALID_KEY_MESSAGE =
            "Wake word detection failed: Porcupine access key is invalid or rejected. " +
                "Generate a new key in Picovoice Console and set PORCUPINE_ACCESS_KEY."
        const val NO_MIC_MESSAGE =
            "Wake word unavailable: no compatible microphone input device found. Manual Talk still works."
    }
}
