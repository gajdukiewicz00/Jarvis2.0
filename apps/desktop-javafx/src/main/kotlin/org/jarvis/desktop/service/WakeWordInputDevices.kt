package org.jarvis.desktop.service

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/** A microphone (input) mixer that can supply Porcupine's capture format. */
data class WakeWordInputDevice(val name: String, val mixerIndex: Int)

/**
 * Enumerates INPUT-capable audio devices for Porcupine's fixed capture format
 * (16 kHz, 16-bit, mono, signed, little-endian).
 *
 * Only mixers that expose a [TargetDataLine] (capture) for the format are kept;
 * output-only ([javax.sound.sampled.SourceDataLine]) mixers are rejected.
 * Duplicate ALSA/PipeWire entries that share a trimmed name are de-duplicated,
 * and the system default input is ordered FIRST so wake-word detection prefers
 * the same microphone that Manual Talk (the default line) uses.
 *
 * The native-enumeration boundary is injectable so [dedupeAndPrefer] — the only
 * non-hardware logic — can be unit-tested without a sound card.
 */
object WakeWordInputDevices {

    /** Porcupine's required capture format. */
    val PORCUPINE_FORMAT: AudioFormat = AudioFormat(16000f, 16, 1, true, false)

    /**
     * List input devices that support [format], default-first and de-duplicated.
     *
     * @param mixerInfos native mixer list (injectable for tests)
     * @param mixerProvider resolves a [Mixer] from its info (injectable for tests)
     * @param defaultName trimmed name of the default input device, ordered first
     */
    fun list(
        format: AudioFormat = PORCUPINE_FORMAT,
        mixerInfos: Array<Mixer.Info> = AudioSystem.getMixerInfo(),
        mixerProvider: (Mixer.Info) -> Mixer = { AudioSystem.getMixer(it) },
        defaultName: String? = defaultDeviceName(format)
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

        return dedupeAndPrefer(supported, defaultName)
    }

    /**
     * The system default input device for [format], or null if none/enumeration
     * fails. Best-effort: the JDK exposes no direct "mixer of the default line"
     * API, so we fall back to the platform default mixer's name.
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

    /**
     * Pure helper: de-duplicate by trimmed name (keeping the first occurrence)
     * and move the [default] device to the front. Input is assumed to already be
     * input-filtered, so nothing is rejected here beyond blank/duplicate names.
     */
    fun dedupeAndPrefer(all: List<WakeWordInputDevice>, default: String?): List<WakeWordInputDevice> {
        val seen = LinkedHashMap<String, WakeWordInputDevice>()
        for (device in all) {
            val key = device.name.trim()
            if (key.isEmpty()) continue
            seen.putIfAbsent(key, device.copy(name = key))
        }
        val deduped = seen.values.toList()

        val defaultTrimmed = default?.trim().orEmpty()
        if (defaultTrimmed.isEmpty()) return deduped

        val (preferred, rest) = deduped.partition { it.name == defaultTrimmed }
        return preferred + rest
    }
}
