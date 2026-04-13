package org.jarvis.apigateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

public final class RecordingHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicReference<ExchangeHandler> handler = new AtomicReference<>(request ->
            new StubResponse(200, Map.of("Content-Type", List.of("application/json")), "{}".getBytes(StandardCharsets.UTF_8)));
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();

    private RecordingHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
        this.server.start();
    }

    public static RecordingHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(new ServerThreadFactory());
            return new RecordingHttpServer(server, executor);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start recording HTTP server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setHandler(ExchangeHandler handler) {
        this.handler.set(handler);
    }

    public RecordedRequest lastRequest() {
        return lastRequest.get();
    }

    public void reset() {
        lastRequest.set(null);
        handler.set(request -> new StubResponse(
                200,
                Map.of("Content-Type", List.of("application/json")),
                "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        RecordedRequest recordedRequest = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                body,
                copyHeaders(exchange));
        lastRequest.set(recordedRequest);

        StubResponse response;
        try {
            response = handler.get().handle(recordedRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response = new StubResponse(
                    500,
                    Map.of("Content-Type", List.of("application/json")),
                    "{\"error\":\"interrupted\"}".getBytes(StandardCharsets.UTF_8));
        }

        response.headers().forEach((name, values) -> exchange.getResponseHeaders().put(name, new ArrayList<>(values)));
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    private Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return Map.copyOf(headers);
    }

    public interface ExchangeHandler {
        StubResponse handle(RecordedRequest request) throws IOException, InterruptedException;
    }

    public record StubResponse(int status, Map<String, List<String>> headers, byte[] body) {
        public static StubResponse json(int status, String body) {
            return new StubResponse(
                    status,
                    Map.of("Content-Type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record RecordedRequest(
            String method,
            String path,
            String query,
            byte[] body,
            Map<String, List<String>> headers) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT)
                            .equals(name.toLowerCase(Locale.ROOT)))
                    .map(Map.Entry::getValue)
                    .filter(values -> values != null && !values.isEmpty())
                    .map(values -> values.getFirst())
                    .findFirst()
                    .orElse(null);
        }

        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class ServerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gateway-test-upstream");
            thread.setDaemon(true);
            return thread;
        }
    }
}
