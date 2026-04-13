package org.jarvis.voicegateway.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class CanonicalWavAudio {

    public static final float CANONICAL_SAMPLE_RATE = 16000f;
    public static final int CANONICAL_SAMPLE_SIZE_BITS = 16;
    public static final int CANONICAL_CHANNELS = 1;

    private CanonicalWavAudio() {
    }

    public static byte[] normalizeToCanonicalWav(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            throw new IllegalArgumentException("WAV payload must not be empty");
        }

        try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData))) {
            AudioFormat sourceFormat = input.getFormat();
            AudioFormat targetFormat = canonicalFormat();

            if (matchesCanonical(sourceFormat)) {
                return wavData;
            }

            try (AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, input);
                    ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = converted.read(buffer)) != -1) {
                    pcmOutput.write(buffer, 0, read);
                }

                byte[] pcmBytes = pcmOutput.toByteArray();
                long frameLength = pcmBytes.length / targetFormat.getFrameSize();
                try (AudioInputStream normalized = new AudioInputStream(
                        new ByteArrayInputStream(pcmBytes),
                        targetFormat,
                        frameLength);
                        ByteArrayOutputStream wavOutput = new ByteArrayOutputStream()) {
                    AudioSystem.write(normalized, AudioFileFormat.Type.WAVE, wavOutput);
                    return wavOutput.toByteArray();
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to normalize WAV to canonical 16kHz mono PCM: " + e.getMessage(), e);
        }
    }

    public static Inspection inspect(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            return new Inspection(false, false, 0f, 0, 0, "WAV payload is empty");
        }

        try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData))) {
            AudioFormat format = input.getFormat();
            boolean canonical = matchesCanonical(format);
            return new Inspection(
                    true,
                    canonical,
                    format.getSampleRate(),
                    format.getChannels(),
                    format.getSampleSizeInBits(),
                    canonical ? "ready" : "non-canonical format");
        } catch (Exception e) {
            return new Inspection(false, false, 0f, 0, 0, e.getMessage());
        }
    }

    public static AudioFormat canonicalFormat() {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                CANONICAL_SAMPLE_RATE,
                CANONICAL_SAMPLE_SIZE_BITS,
                CANONICAL_CHANNELS,
                2,
                CANONICAL_SAMPLE_RATE,
                false);
    }

    private static boolean matchesCanonical(AudioFormat format) {
        return format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && format.getSampleRate() == CANONICAL_SAMPLE_RATE
                && format.getSampleSizeInBits() == CANONICAL_SAMPLE_SIZE_BITS
                && format.getChannels() == CANONICAL_CHANNELS
                && format.getFrameSize() == 2
                && format.getFrameRate() == CANONICAL_SAMPLE_RATE
                && !format.isBigEndian();
    }

    public record Inspection(
            boolean valid,
            boolean canonical,
            float sampleRate,
            int channels,
            int sampleSizeBits,
            String reason) {
    }
}
