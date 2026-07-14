package org.jarvis.desktop.service

/** The two wake-word engines, in preference order. */
enum class WakeWordMode { CUSTOM_RU, BUILTIN_JARVIS }

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
 * of its own: every attempt to build+start an engine, and any cleanup of a probe
 * handle, is delegated to injected seams. This makes the whole custom→built-in
 * fallback decision tree unit-testable with fakes.
 *
 * Order of [initialize]:
 *  1. no access key            → Disabled("access_key_missing")
 *  2. no compatible mic        → Disabled("no_input_device")
 *  3. custom model (if present & readable) on each device, default-first;
 *     first Success → Enabled(CUSTOM_RU). All fail → mark incompatible, fall through.
 *  4. built-in Jarvis on each device; first Success → Enabled(BUILTIN_JARVIS).
 *  5. everything failed        → Disabled("all_failed")
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
    private val stopProbe: (Any) -> Unit = {}
) {

    fun initialize(): WakeWordInitOutcome {
        if (!accessKeyPresent) {
            return disabled(
                reason = "access_key_missing",
                userMessage = "Wake word unavailable. Manual Talk still works. " +
                    "Reason: Porcupine access key not configured.",
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null
            )
        }

        if (devices.isEmpty()) {
            return disabled(
                reason = "no_input_device",
                userMessage = "Wake word unavailable: no compatible input device for Porcupine. " +
                    "Manual Talk still works.",
                mode = "NONE",
                customCompatible = null,
                builtInAvailable = null,
                selectedDevice = null,
                lastFailure = null
            )
        }

        var customTried = false
        var lastFailure: WakeWordAttemptResult.Failure? = null

        if (customModel != null && customModel.exists && customModel.readable) {
            customTried = true
            for (device in devices) {
                when (val result = attempt(WakeWordAttempt(WakeWordMode.CUSTOM_RU, device))) {
                    is WakeWordAttemptResult.Success -> {
                        persistDevice(device)
                        return enabled(
                            handle = result.handle,
                            mode = WakeWordMode.CUSTOM_RU,
                            device = device,
                            customCompatible = true,
                            builtInAvailable = null,
                            lastFailure = null
                        )
                    }
                    is WakeWordAttemptResult.Failure -> lastFailure = result
                }
            }
        }

        for (device in devices) {
            when (val result = attempt(WakeWordAttempt(WakeWordMode.BUILTIN_JARVIS, device))) {
                is WakeWordAttemptResult.Success -> {
                    persistDevice(device)
                    return enabled(
                        handle = result.handle,
                        mode = WakeWordMode.BUILTIN_JARVIS,
                        device = device,
                        customCompatible = if (customTried) false else null,
                        builtInAvailable = true,
                        lastFailure = lastFailure
                    )
                }
                is WakeWordAttemptResult.Failure -> lastFailure = result
            }
        }

        val detail = if (customTried) {
            "incompatible custom model and built-in Jarvis init failed"
        } else {
            "built-in Jarvis init failed"
        }
        return disabled(
            reason = "all_failed",
            userMessage = "Wake word unavailable. Manual Talk still works. Reason: $detail.",
            mode = "NONE",
            customCompatible = if (customTried) false else null,
            builtInAvailable = false,
            selectedDevice = devices.firstOrNull()?.name,
            lastFailure = lastFailure
        )
    }

    /**
     * Dry probe for "Test Wake Word Setup": attempts custom then built-in on the
     * preferred (first) device, recording compatibility. Any Success handle is
     * released via [stopProbe] so the microphone is never left open.
     */
    fun diagnose(): WakeWordDiagnostics {
        if (!accessKeyPresent) {
            return build("NONE", null, null, null, null, "access_key_missing")
        }
        val device = devices.firstOrNull()
            ?: return build("NONE", null, null, null, null, "no_input_device")

        var customCompatible: Boolean? = null
        var builtInAvailable: Boolean? = null
        var lastFailure: WakeWordAttemptResult.Failure? = null

        if (customModel != null && customModel.exists && customModel.readable) {
            when (val r = attempt(WakeWordAttempt(WakeWordMode.CUSTOM_RU, device))) {
                is WakeWordAttemptResult.Success -> {
                    customCompatible = true
                    stopProbe(r.handle)
                }
                is WakeWordAttemptResult.Failure -> {
                    customCompatible = false
                    lastFailure = r
                }
            }
        }

        when (val r = attempt(WakeWordAttempt(WakeWordMode.BUILTIN_JARVIS, device))) {
            is WakeWordAttemptResult.Success -> {
                builtInAvailable = true
                stopProbe(r.handle)
            }
            is WakeWordAttemptResult.Failure -> {
                builtInAvailable = false
                lastFailure = r
            }
        }

        val mode = when {
            customCompatible == true -> "CUSTOM_RU"
            builtInAvailable == true -> "BUILTIN_JARVIS"
            else -> "NONE"
        }
        val reason = if (mode == "NONE") "all_failed" else null
        return build(mode, customCompatible, builtInAvailable, device.name, lastFailure, reason)
    }

    // ── diagnostics assembly ────────────────────────────────────────────────

    private fun enabled(
        handle: Any,
        mode: WakeWordMode,
        device: WakeWordInputDevice?,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        lastFailure: WakeWordAttemptResult.Failure?
    ): WakeWordInitOutcome.Enabled = WakeWordInitOutcome.Enabled(
        handle = handle,
        mode = mode,
        device = device,
        diagnostics = build(mode.name, customCompatible, builtInAvailable, device?.name, lastFailure, null)
    )

    private fun disabled(
        reason: String,
        userMessage: String,
        mode: String,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        selectedDevice: String?,
        lastFailure: WakeWordAttemptResult.Failure?
    ): WakeWordInitOutcome.Disabled = WakeWordInitOutcome.Disabled(
        reason = reason,
        userMessage = userMessage,
        diagnostics = build(mode, customCompatible, builtInAvailable, selectedDevice, lastFailure, reason)
    )

    private fun build(
        mode: String,
        customCompatible: Boolean?,
        builtInAvailable: Boolean?,
        selectedDevice: String?,
        lastFailure: WakeWordAttemptResult.Failure?,
        reason: String?
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
        recommendedFix = recommendedFix(customCompatible, builtInAvailable)
    )

    private fun formatError(failure: WakeWordAttemptResult.Failure): String =
        failure.message?.takeIf { it.isNotBlank() }
            ?: failure.messageStack.firstOrNull()
            ?: failure.exceptionClass

    private fun recommendedFix(customCompatible: Boolean?, builtInAvailable: Boolean?): String? = when {
        !accessKeyPresent -> "set PORCUPINE_ACCESS_KEY"
        devices.isEmpty() -> "choose another input device"
        builtInAvailable == false -> "check Porcupine access key validity"
        customCompatible == false -> "regenerate jarvis_ru.ppn for Porcupine 4.x or it will use built-in Jarvis"
        else -> null
    }
}
