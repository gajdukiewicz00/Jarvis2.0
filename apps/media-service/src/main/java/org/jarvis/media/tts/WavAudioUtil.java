package org.jarvis.media.tts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal, dependency-free WAV (RIFF/PCM) header reader. Used by {@link PiperTtsProvider}
 * to compute the real synthesized-clip duration from a Piper output file, replacing the
 * text-length heuristic {@link NeutralRussianTtsProvider} uses for its placeholder audio.
 *
 * <p>Walks RIFF sub-chunks (rather than assuming the canonical 44-byte layout) so an
 * extra {@code LIST}/metadata chunk before {@code fmt }/{@code data} does not break
 * parsing. Never throws on malformed input — an unparseable file yields a duration of
 * 0, which callers treat the same as "no audio produced".</p>
 */
public final class WavAudioUtil {

    private static final int RIFF_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int FMT_CHUNK_MIN_BYTES = 16;

    private WavAudioUtil() {
    }

    public static long durationMillis(Path wavFile) throws IOException {
        return durationMillis(Files.readAllBytes(wavFile));
    }

    /**
     * Parses a RIFF/WAVE PCM byte array and returns the playable duration in whole
     * milliseconds, or 0 if the bytes are not a recognizable/complete WAV.
     */
    public static long durationMillis(byte[] bytes) {
        if (bytes == null || bytes.length < RIFF_HEADER_BYTES
                || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        Integer sampleRate = null;
        Integer channels = null;
        Integer bitsPerSample = null;
        Long dataBytes = null;

        int pos = RIFF_HEADER_BYTES;
        while (pos + CHUNK_HEADER_BYTES <= bytes.length) {
            String chunkId = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long chunkSize = Integer.toUnsignedLong(buf.getInt(pos + 4));
            int chunkDataStart = pos + CHUNK_HEADER_BYTES;

            if ("fmt ".equals(chunkId) && chunkDataStart + FMT_CHUNK_MIN_BYTES <= bytes.length) {
                channels = Short.toUnsignedInt(buf.getShort(chunkDataStart + 2));
                sampleRate = buf.getInt(chunkDataStart + 4);
                bitsPerSample = Short.toUnsignedInt(buf.getShort(chunkDataStart + 14));
            } else if ("data".equals(chunkId)) {
                dataBytes = Math.min(chunkSize, Math.max(0, (long) bytes.length - chunkDataStart));
            }

            // RIFF chunks are word-aligned: an odd-sized chunk is padded by one byte.
            long advance = chunkSize + (chunkSize % 2);
            long next = (long) chunkDataStart + advance;
            if (advance < 0 || next <= pos || next > Integer.MAX_VALUE) {
                break; // malformed/non-advancing size — stop rather than loop forever
            }
            pos = (int) next;
        }

        return toDurationMillis(sampleRate, channels, bitsPerSample, dataBytes);
    }

    private static long toDurationMillis(Integer sampleRate, Integer channels, Integer bitsPerSample, Long dataBytes) {
        if (sampleRate == null || channels == null || bitsPerSample == null || dataBytes == null
                || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
            return 0;
        }
        long byteRate = (long) sampleRate * channels * (bitsPerSample / 8);
        if (byteRate <= 0) {
            return 0;
        }
        return Math.round(dataBytes * 1000.0 / byteRate);
    }

    private static boolean tag(byte[] bytes, int offset, String expected) {
        if (offset + 4 > bytes.length) {
            return false;
        }
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII).equals(expected);
    }
}
