import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

public class VoiceWsScenario {

    private static final int DEFAULT_CHUNK_BYTES = 3200;
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("Usage: java VoiceWsScenario.java <url> <output> <token> <userId> <username> <scenario> <correlationId> <language> [wavFile]");
            System.exit(1);
        }

        URI uri = URI.create(args[0]);
        Path output = Path.of(args[1]);
        String token = args[2];
        String userId = args[3];
        String username = args[4];
        String scenario = args[5];
        String correlationId = args[6];
        String language = args[7];
        Path wavFile = args.length > 8 ? Path.of(args[8]) : null;

        Files.createDirectories(output.getParent());
        Files.writeString(output, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Listener listener = new Listener(output);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        WebSocket webSocket = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", userId)
                .header("X-Username", username)
                .header("X-User-Roles", "USER")
                .buildAsync(uri, listener)
                .join();

        listener.awaitOpen(5_000L);
        waitFor(listener, l -> l.hasState("CONNECTED"), 5_000L, "CONNECTED state");

        String normalizedScenario = scenario.toLowerCase(Locale.ROOT);
        switch (normalizedScenario) {
            case "roundtrip" -> runRoundTrip(webSocket, listener, correlationId, language, wavFile);
            case "start-rejected" -> runStartRejected(webSocket, listener, correlationId, language);
            case "audio-before-start" -> runAudioBeforeStart(webSocket, listener, wavFile);
            case "duplicate-start" -> runDuplicateStart(webSocket, listener, correlationId, language);
            case "end-without-audio" -> runEndWithoutAudio(webSocket, listener, correlationId, language);
            case "timeout" -> runTimeout(webSocket, listener, correlationId, language);
            case "config-invalid" -> runInvalidConfig(webSocket, listener);
            default -> throw new IllegalArgumentException("Unsupported scenario: " + scenario);
        }

        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            listener.awaitClosed(2_000L);
        } catch (RuntimeException ignored) {
        }
    }

    private static void runRoundTrip(
            WebSocket webSocket,
            Listener listener,
            String correlationId,
            String language,
            Path wavFile) throws Exception {
        byte[] pcm = extractPcm(wavFile);

        sendText(webSocket, listener, """
                {"type":"CONFIG","config":{"language":"%s"}}
                """.formatted(escape(language)).trim());
        waitFor(listener, l -> l.hasState("CONFIGURED"), 5_000L, "CONFIGURED state");

        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasState("STARTED"), 5_000L, "STARTED state");

        for (int offset = 0; offset < pcm.length; offset += DEFAULT_CHUNK_BYTES) {
            int size = Math.min(DEFAULT_CHUNK_BYTES, pcm.length - offset);
            byte[] chunk = new byte[size];
            System.arraycopy(pcm, offset, chunk, 0, size);
            sendBinary(webSocket, listener, chunk);
            Thread.sleep(20L);
        }
        sendText(webSocket, listener, """
                {"type":"END","correlationId":"%s"}
                """.formatted(escape(correlationId)).trim());

        waitFor(listener, l -> l.hasMessageType("TRANSCRIPT_FINAL"), DEFAULT_TIMEOUT_MS, "TRANSCRIPT_FINAL");
        waitFor(listener, l -> l.hasMessageType("RESPONSE"), DEFAULT_TIMEOUT_MS, "RESPONSE");
        waitFor(listener, l -> l.hasState("DONE"), DEFAULT_TIMEOUT_MS, "DONE state");
    }

    private static void runAudioBeforeStart(
            WebSocket webSocket,
            Listener listener,
            Path wavFile) throws Exception {
        byte[] pcm = extractPcm(wavFile);
        byte[] firstChunk = new byte[Math.min(DEFAULT_CHUNK_BYTES, pcm.length)];
        System.arraycopy(pcm, 0, firstChunk, 0, firstChunk.length);
        sendBinary(webSocket, listener, firstChunk);
        waitFor(listener, l -> l.hasErrorCode("AUDIO_BEFORE_START"), 5_000L, "AUDIO_BEFORE_START");
    }

    private static void runStartRejected(
            WebSocket webSocket,
            Listener listener,
            String correlationId,
            String language) throws Exception {
        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasErrorCode("STT_UNAVAILABLE"), 5_000L, "STT_UNAVAILABLE");
        waitFor(listener, l -> l.hasResponseAction("STT_UNAVAILABLE"), 5_000L, "STT_UNAVAILABLE response");
        waitFor(listener, l -> l.hasState("STT_UNAVAILABLE"), 5_000L, "STT_UNAVAILABLE state");
    }

    private static void runDuplicateStart(
            WebSocket webSocket,
            Listener listener,
            String correlationId,
            String language) throws Exception {
        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasState("STARTED"), 5_000L, "STARTED state");

        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s-dupe","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasErrorCode("DUPLICATE_START"), 5_000L, "DUPLICATE_START");
    }

    private static void runEndWithoutAudio(
            WebSocket webSocket,
            Listener listener,
            String correlationId,
            String language) throws Exception {
        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasState("STARTED"), 5_000L, "STARTED state");

        sendText(webSocket, listener, """
                {"type":"END","correlationId":"%s"}
                """.formatted(escape(correlationId)).trim());
        waitFor(listener, l -> l.hasErrorCode("NO_AUDIO_RECEIVED"), 5_000L, "NO_AUDIO_RECEIVED");
        waitFor(listener, l -> l.hasState("DONE"), 5_000L, "DONE state");
    }

    private static void runTimeout(
            WebSocket webSocket,
            Listener listener,
            String correlationId,
            String language) throws Exception {
        sendText(webSocket, listener, """
                {"type":"START","correlationId":"%s","language":"%s"}
                """.formatted(escape(correlationId), escape(language)).trim());
        waitFor(listener, l -> l.hasState("STARTED"), 5_000L, "STARTED state");

        sendText(webSocket, listener, """
                {"type":"TIMEOUT","correlationId":"%s"}
                """.formatted(escape(correlationId)).trim());
        waitFor(listener, l -> l.hasErrorCode("TIMEOUT"), 5_000L, "TIMEOUT error");
        waitFor(listener, l -> l.hasResponseAction("STT_TIMEOUT"), 5_000L, "STT_TIMEOUT response");
        waitFor(listener, l -> l.hasState("TIMEOUT"), 5_000L, "TIMEOUT state");
    }

    private static void runInvalidConfig(WebSocket webSocket, Listener listener) throws Exception {
        sendText(webSocket, listener, """
                {"type":"CONFIG","config":{}}
                """.trim());
        waitFor(listener, l -> l.hasErrorCode("CONFIG_INVALID"), 5_000L, "CONFIG_INVALID");
    }

    private static void sendText(WebSocket webSocket, Listener listener, String payload) {
        listener.recordOutbound("OUT " + payload);
        webSocket.sendText(payload, true).join();
    }

    private static void sendBinary(WebSocket webSocket, Listener listener, byte[] payload) {
        listener.recordOutbound("OUT_BINARY bytes=" + payload.length);
        webSocket.sendBinary(ByteBuffer.wrap(payload), true).join();
    }

    private static void waitFor(
            Listener listener,
            Predicate<Listener> predicate,
            long timeoutMs,
            String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (predicate.test(listener)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("Timed out waiting for " + description + ". Recent trace: " + listener.recentTrace());
    }

    private static byte[] extractPcm(Path wavFile) throws IOException {
        if (wavFile == null) {
            throw new IllegalArgumentException("A WAV file is required for this scenario");
        }
        byte[] wavData = Files.readAllBytes(wavFile);
        if (wavData.length < 44) {
            throw new IllegalArgumentException("WAV file is too small: " + wavFile);
        }

        ByteBuffer buffer = ByteBuffer.wrap(wavData).order(ByteOrder.LITTLE_ENDIAN);
        byte[] riff = new byte[4];
        buffer.get(riff);
        if (!"RIFF".equals(new String(riff))) {
            throw new IllegalArgumentException("Missing RIFF header: " + wavFile);
        }
        buffer.getInt();
        byte[] wave = new byte[4];
        buffer.get(wave);
        if (!"WAVE".equals(new String(wave))) {
            throw new IllegalArgumentException("Missing WAVE header: " + wavFile);
        }

        while (buffer.remaining() >= 8) {
            byte[] chunkId = new byte[4];
            buffer.get(chunkId);
            int chunkSize = buffer.getInt();
            String name = new String(chunkId);
            if ("fmt ".equals(name)) {
                short audioFormat = buffer.getShort();
                short channels = buffer.getShort();
                int sampleRate = buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                short bitsPerSample = buffer.getShort();
                int remaining = chunkSize - 16;
                if (remaining > 0) {
                    buffer.position(buffer.position() + remaining);
                }
                if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
                    throw new IllegalArgumentException(
                            "Unsupported WAV format: format=" + audioFormat
                                    + ", channels=" + channels
                                    + ", sampleRate=" + sampleRate
                                    + ", bitsPerSample=" + bitsPerSample);
                }
                while (buffer.remaining() >= 8) {
                    byte[] dataChunkId = new byte[4];
                    buffer.get(dataChunkId);
                    int dataSize = buffer.getInt();
                    if ("data".equals(new String(dataChunkId))) {
                        byte[] pcm = new byte[dataSize];
                        buffer.get(pcm);
                        return pcm;
                    }
                    buffer.position(buffer.position() + dataSize);
                }
                break;
            }
            buffer.position(buffer.position() + chunkSize);
        }

        throw new IllegalArgumentException("WAV data chunk not found: " + wavFile);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Listener implements WebSocket.Listener {
        private final Path output;
        private final CountDownLatch open = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final List<String> trace = new ArrayList<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private Listener(Path output) {
            this.output = output;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            recordInbound("EVENT OPEN");
            open.countDown();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                recordInbound("IN " + textBuffer);
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.write(chunk, 0, chunk.length);
            if (last) {
                recordInbound("IN_BINARY bytes=" + binaryBuffer.size());
                binaryBuffer.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            recordInbound("EVENT CLOSE status=" + statusCode + " reason=" + escape(reason));
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            recordInbound("EVENT ERROR message=" + escape(error != null ? error.getMessage() : null));
            closed.countDown();
        }

        private synchronized void recordInbound(String line) {
            trace.add(line);
            appendLine(output, line);
        }

        private synchronized void recordOutbound(String line) {
            trace.add(line);
            appendLine(output, line);
        }

        private synchronized boolean hasState(String state) {
            return trace.stream()
                    .filter(line -> line.startsWith("IN "))
                    .map(line -> line.substring(3))
                    .filter(payload -> "STATE".equals(jsonField(payload, "type")))
                    .anyMatch(payload -> state.equals(jsonField(payload, "state")));
        }

        private synchronized boolean hasErrorCode(String code) {
            return trace.stream()
                    .filter(line -> line.startsWith("IN "))
                    .map(line -> line.substring(3))
                    .filter(payload -> "ERROR".equals(jsonField(payload, "type")))
                    .anyMatch(payload -> code.equals(jsonField(payload, "code")));
        }

        private synchronized boolean hasResponseAction(String action) {
            return trace.stream()
                    .filter(line -> line.startsWith("IN "))
                    .map(line -> line.substring(3))
                    .filter(payload -> "RESPONSE".equals(jsonField(payload, "type")))
                    .anyMatch(payload -> action.equals(jsonField(payload, "action")));
        }

        private synchronized boolean hasMessageType(String type) {
            return trace.stream()
                    .filter(line -> line.startsWith("IN "))
                    .map(line -> line.substring(3))
                    .anyMatch(payload -> type.equals(jsonField(payload, "type")));
        }

        private synchronized String recentTrace() {
            int fromIndex = Math.max(0, trace.size() - 8);
            return String.join(" | ", trace.subList(fromIndex, trace.size()));
        }

        private void awaitOpen(long timeoutMs) throws InterruptedException {
            if (!open.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for websocket open");
            }
        }

        private void awaitClosed(long timeoutMs) throws InterruptedException {
            closed.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private static String jsonField(String payload, String field) {
            if (payload == null || field == null || !payload.startsWith("{")) {
                return null;
            }
            String key = "\"" + field + "\"";
            int keyIndex = payload.indexOf(key);
            if (keyIndex < 0) {
                return null;
            }
            int colonIndex = payload.indexOf(':', keyIndex + key.length());
            if (colonIndex < 0) {
                return null;
            }
            int firstQuote = payload.indexOf('"', colonIndex + 1);
            if (firstQuote < 0) {
                return null;
            }
            int cursor = firstQuote + 1;
            boolean escaped = false;
            StringBuilder value = new StringBuilder();
            while (cursor < payload.length()) {
                char current = payload.charAt(cursor);
                if (escaped) {
                    value.append(current);
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    return value.toString();
                } else {
                    value.append(current);
                }
                cursor++;
            }
            return null;
        }

        private static void appendLine(Path path, String value) {
            try {
                Files.writeString(
                        path,
                        value + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
