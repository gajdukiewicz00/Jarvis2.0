package org.jarvis.desktop.service

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/** A microphone (input) mixer that can supply Porcupine's capture format. */
data class WakeWordInputDevice(val name: String, val mixerIndex: Int)

/** An enumerated device that was NOT offered as a wake-word microphone, with why. */
data class RejectedInputDevice(val name: String, val reason: String)

/**
 * Result of [WakeWordInputDevices.classify]: the usable microphones (ordered so
 * real mics come first) and the devices that were dropped (with a reason) so
 * diagnostics can explain, e.g., why "alsa_playback.java [default]" was skipped.
 */
data class DeviceClassification(
    val accepted: List<WakeWordInputDevice>,
    val rejected: List<RejectedInputDevice>
)

/**
 * Enumerates INPUT-capable audio devices for Porcupine's fixed capture format
 * (16 kHz, 16-bit, mono, signed, little-endian) and classifies them.
 *
 * Two independent problems this guards against:
 *  1. A JDK/ALSA/PipeWire PLAYBACK mixer (e.g. "alsa_playback.java [default]")
 *     can advertise a capture line yet be an output/monitor device — selecting it
 *     as "the microphone" silently breaks wake-word detection. [classify] rejects
 *     anything whose name looks like a playback/output/monitor/sink/speaker.
 *  2. The system default is often that playback device, so we must NOT auto-front
 *     the raw default. Instead we order REAL microphones (names hinting mic / C4K /
 *     T1 / USB / plughw) first so a genuine capture device is tried before anything
 *     ambiguous.
 *
 * The native-enumeration boundary is injectable so [classify] — the only
 * non-hardware logic — can be unit-tested without a sound card.
 */
object WakeWordInputDevices {

    /** Porcupine's required capture format. */
    val PORCUPINE_FORMAT: AudioFormat = AudioFormat(16000f, 16, 1, true, false)

    /** Name fragments that mark a device as playback/output — never a microphone. */
    val REJECT_SUBSTRINGS: List<String> = listOf("playback", "output", "monitor", "sink", "speaker")

    /** Name fragments that mark a device as a real capture microphone (front these). */
    val PREFER_SUBSTRINGS: List<String> = listOf("microphone", "mic", "c4k", "t1", "usb", "plughw")

    /**
     * List usable input devices for [format]: real microphones first, playback
     * devices removed, de-duplicated by trimmed name. Equivalent to
     * [listWithClassification]`(...).accepted`.
     */
    fun list(
        format: AudioFormat = PORCUPINE_FORMAT,
        mixerInfos: Array<Mixer.Info> = AudioSystem.getMixerInfo(),
        mixerProvider: (Mixer.Info) -> Mixer = { AudioSystem.getMixer(it) },
        defaultName: String? = defaultDeviceName(format)
    ): List<WakeWordInputDevice> =
        listWithClassification(format, mixerInfos, mixerProvider, defaultName).accepted

    /**
     * Enumerate input devices and classify them into accepted microphones and
     * rejected playback/output devices. Diagnostics use both halves.
     *
     * @param mixerInfos native mixer list (injectable for tests)
     * @param mixerProvider resolves a [Mixer] from its info (injectable for tests)
     * @param defaultName trimmed name of the default input device (used only as a
     *   tie-breaker among preferred mics; a rejected default is never selected)
     */
    fun listWithClassification(
        format: AudioFormat = PORCUPINE_FORMAT,
        mixerInfos: Array<Mixer.Info> = AudioSystem.getMixerInfo(),
        mixerProvider: (Mixer.Info) -> Mixer = { AudioSystem.getMixer(it) },
        defaultName: String? = defaultDeviceName(format)
    ): DeviceClassification = classify(enumerate(format, mixerInfos, mixerProvider), defaultName)

    /**
     * The system default input device for [format], or null if none/enumeration
     * fails, or if the default is itself a rejected playback device.
     */
    fun defaultDevice(format: AudioFormat = PORCUPINE_FORMAT): WakeWordInputDevice? {
        val name = defaultDeviceName(format) ?: return null
        return list(format = format, defaultName = name).firstOrNull { it.name == name }
    }

    /** Best-effort trimmed name of the default capture device. */
    fun defaultDeviceName(format: AudioFormat = PORCUPINE_FORMAT): String? {
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                return AudioSystem.getMixer(null)?.mixerInfo?.name?.trim()
            }
            // The default line does not surface its mixer; the platform default
            // mixer name is the closest stable identifier we can report.
            AudioSystem.getMixer(null)?.mixerInfo?.name?.trim()
        } catch (_: Exception) {
            null
        }
    }

    /** True when [name] looks like a playback/output device rather than a mic. */
    fun looksLikePlayback(name: String): Boolean {
        val lower = name.trim().lowercase()
        return REJECT_SUBSTRINGS.any { lower.contains(it) }
    }

    /**
     * PURE classification of enumerated input devices.
     *
     *  - Reject any device whose (lowercased, trimmed) name contains a
     *    [REJECT_SUBSTRINGS] fragment — this removes playback/monitor devices
     *    that merely advertise a capture line (e.g. "alsa_playback.java [default]").
     *  - De-duplicate accepted devices by trimmed name (first occurrence wins).
     *  - Order accepted devices: those matching any [PREFER_SUBSTRINGS] fragment
     *    FIRST (relative order preserved), then the rest. The [default] is NOT
     *    auto-fronted; it is used only to break ties among preferred mics, and a
     *    rejected default can never be selected (it was already dropped).
     */
    fun classify(all: List<WakeWordInputDevice>, default: String?): DeviceClassification {
        val rejected = mutableListOf<RejectedInputDevice>()
        val acceptedByName = LinkedHashMap<String, WakeWordInputDevice>()

        for (device in all) {
            val key = device.name.trim()
            if (key.isEmpty()) continue
            if (looksLikePlayback(key)) {
                rejected += RejectedInputDevice(key, "playback/output/monitor device")
                continue
            }
            acceptedByName.putIfAbsent(key, device.copy(name = key))
        }

        val deduped = acceptedByName.values.toList()
        val (preferred, rest) = deduped.partition { isPreferred(it.name) }
        val ordered = preferred + rest

        // Front an accepted, PREFERRED default so wake-word pins the same real mic
        // Manual Talk would use — without letting a plain default jump ahead of a
        // C4K/T1/USB mic (that would violate preferred-before-rest).
        val defaultTrimmed = default?.trim().orEmpty()
        val finalOrder = if (defaultTrimmed.isNotEmpty()) {
            val idx = ordered.indexOfFirst { it.name == defaultTrimmed }
            if (idx > 0 && isPreferred(ordered[idx].name)) {
                listOf(ordered[idx]) + ordered.filterIndexed { i, _ -> i != idx }
            } else {
                ordered
            }
        } else {
            ordered
        }

        return DeviceClassification(accepted = finalOrder, rejected = rejected)
    }

    private fun isPreferred(name: String): Boolean {
        val lower = name.lowercase()
        return PREFER_SUBSTRINGS.any { lower.contains(it) }
    }

    /** Native boundary: keep only mixers that can open a capture line for [format]. */
    private fun enumerate(
        format: AudioFormat,
        mixerInfos: Array<Mixer.Info>,
        mixerProvider: (Mixer.Info) -> Mixer
    ): List<WakeWordInputDevice> {
        val dataLineInfo = DataLine.Info(TargetDataLine::class.java, format)
        val supported = mutableListOf<WakeWordInputDevice>()

        mixerInfos.forEachIndexed { index, info ->
            val mixer = try {
                mixerProvider(info)
            } catch (_: Exception) {
                return@forEachIndexed
            }
            // Keep ONLY mixers that can open a capture line for the format.
            // This rejects output-only (SourceDataLine) mixers.
            if (mixer.isLineSupported(dataLineInfo)) {
                supported += WakeWordInputDevice(info.name.trim(), index)
            }
        }
        return supported
    }
}
