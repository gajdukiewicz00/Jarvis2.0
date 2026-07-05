package org.jarvis.media.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WavAudioUtilTest {

    @TempDir
    Path tmp;

    @Test
    void computesDurationForCanonicalPcmWav() {
        // 16kHz mono 16-bit, 1 second of audio -> 32000 bytes of data.
        byte[] wav = wav(16000, 1, 16, 32000, false);
        assertThat(WavAudioUtil.durationMillis(wav)).isEqualTo(1000);
    }

    @Test
    void computesDurationForStereo8BitAtHalfSecond() {
        // 8kHz stereo 8-bit -> byteRate = 8000*2*1 = 16000 bytes/sec; 8000 bytes = 500ms.
        byte[] wav = wav(8000, 2, 8, 8000, false);
        assertThat(WavAudioUtil.durationMillis(wav)).isEqualTo(500);
    }

    @Test
    void skipsAnExtraOddSizedChunkBeforeTheDataChunk() {
        // An odd-sized LIST chunk (word-aligned padding) sits between fmt and data;
        // parsing must still find sampleRate/data correctly.
        byte[] wav = wav(16000, 1, 16, 32000, true);
        assertThat(WavAudioUtil.durationMillis(wav)).isEqualTo(1000);
    }

    @Test
    void tooShortByteArrayReturnsZero() {
        assertThat(WavAudioUtil.durationMillis(new byte[]{'R', 'I', 'F', 'F'})).isZero();
    }

    @Test
    void missingRiffOrWaveTagReturnsZero() {
        byte[] bogus = new byte[64];
        System.arraycopy("NOTA".getBytes(StandardCharsets.US_ASCII), 0, bogus, 0, 4);
        assertThat(WavAudioUtil.durationMillis(bogus)).isZero();
    }

    @Test
    void zeroSampleRateReturnsZero() {
        byte[] wav = wav(0, 1, 16, 1000, false);
        assertThat(WavAudioUtil.durationMillis(wav)).isZero();
    }

    @Test
    void nullBytesReturnZero() {
        assertThat(WavAudioUtil.durationMillis((byte[]) null)).isZero();
    }

    @Test
    void readsDurationFromAFileOnDisk() throws IOException {
        Path file = tmp.resolve("clip.wav");
        Files.write(file, wav(16000, 1, 16, 16000, false));
        assertThat(WavAudioUtil.durationMillis(file)).isEqualTo(500);
    }

    /** Builds a minimal RIFF/WAVE PCM byte array, optionally with an extra odd-sized chunk before "data". */
    private byte[] wav(int sampleRate, int channels, int bitsPerSample, int dataBytes, boolean withExtraChunk) {
        try {
            ByteArrayOutputStream fmtAndData = new ByteArrayOutputStream();
            writeChunk(fmtAndData, "fmt ", fmtBody(sampleRate, channels, bitsPerSample));
            if (withExtraChunk) {
                writeChunk(fmtAndData, "LIST", new byte[]{1, 2, 3, 4, 5}); // odd size -> 1 pad byte
            }
            writeChunk(fmtAndData, "data", new byte[dataBytes]);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            out.write(int32LE(4 + fmtAndData.size())); // "WAVE" + chunks
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            out.write(fmtAndData.toByteArray());
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] fmtBody(int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(int16LE(1)); // PCM
        body.write(int16LE(channels));
        body.write(int32LE(sampleRate));
        body.write(int32LE(byteRate));
        body.write(int16LE(blockAlign));
        body.write(int16LE(bitsPerSample));
        return body.toByteArray();
    }

    private void writeChunk(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(int32LE(body.length));
        out.write(body);
        if (body.length % 2 != 0) {
            out.write(0); // word-alignment pad byte
        }
    }

    private byte[] int32LE(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)};
    }

    private byte[] int16LE(int value) {
        return new byte[]{(byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)};
    }
}
