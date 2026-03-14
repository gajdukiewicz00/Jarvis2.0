import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class WsProbe {

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("Usage: java WsProbe.java <mode> <url> <output> <ready> <token> <userId> <username>");
            System.exit(1);
        }

        String mode = args[0];
        URI uri = URI.create(args[1]);
        Path output = Path.of(args[2]);
        Path ready = Path.of(args[3]);
        String token = args[4];
        String userId = args[5];
        String username = args[6];

        Files.createDirectories(output.getParent());
        Files.createDirectories(ready.getParent());
        Files.writeString(output, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(ready);

        CountDownLatch closed = new CountDownLatch(1);
        Listener listener = new Listener(mode, output, ready, userId, username, closed);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        WebSocket.Builder builder = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", userId)
                .header("X-Username", username)
                .header("X-User-Roles", "USER");

        builder.buildAsync(uri, listener).join();
        closed.await();
    }

    private static final class Listener implements WebSocket.Listener {
        private final String mode;
        private final Path output;
        private final Path ready;
        private final String userId;
        private final String username;
        private final CountDownLatch closed;
        private final StringBuilder textBuffer = new StringBuilder();

        private Listener(
                String mode,
                Path output,
                Path ready,
                String userId,
                String username,
                CountDownLatch closed
        ) {
            this.mode = mode;
            this.output = output;
            this.ready = ready;
            this.userId = userId;
            this.username = username;
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            try {
                Files.writeString(ready, "OPEN\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                if ("pc".equalsIgnoreCase(mode)) {
                    webSocket.sendText(pcIdentifyMessage(userId, username), true);
                } else if ("voice".equalsIgnoreCase(mode)) {
                    webSocket.sendText("{\"type\":\"CONFIG\",\"config\":{\"language\":\"ru-RU\"}}", true);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                appendLine(output, textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            appendLine(output, "{\"type\":\"BINARY\",\"bytes\":" + data.remaining() + "}");
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            appendLine(output, "{\"type\":\"CLOSE\",\"status\":" + statusCode + ",\"reason\":\"" + escape(reason) + "\"}");
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            appendLine(output, "{\"type\":\"ERROR\",\"message\":\"" + escape(error.getMessage()) + "\"}");
            closed.countDown();
        }

        private static void appendLine(Path path, String value) {
            try {
                Files.writeString(path, value + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static String pcIdentifyMessage(String userId, String username) {
            return "{"
                    + "\"type\":\"IDENTIFY\","
                    + "\"client\":\"desktop\","
                    + "\"clientId\":\"desktop-" + escape(userId) + "\","
                    + "\"userId\":\"" + escape(userId) + "\","
                    + "\"username\":\"" + escape(username) + "\""
                    + "}";
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
