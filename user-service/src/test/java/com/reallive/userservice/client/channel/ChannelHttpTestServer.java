package com.reallive.userservice.client.channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP stub for exercising the real Channel Feign client and remote side effects. */
public class ChannelHttpTestServer implements AutoCloseable {

    public enum Mode { SUCCESS, HTTP_ERROR, INVALID_JSON, EMPTY_BODY, FALSE_RESULT, NULL_RESULT, LOST_RESPONSE }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private final Set<UUID> channels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.SUCCESS;
    private volatile int errorStatus = 503;
    private volatile CountDownLatch releaseResponse = new CountDownLatch(1);

    public ChannelHttpTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/channel/internal/api/channels", this::handle);
            server.start();
        } catch (IOException exception) {
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start Channel HTTP test server", exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/channel";
    }

    public void reset() {
        releaseResponse.countDown();
        releaseResponse = new CountDownLatch(1);
        requests.clear();
        channels.clear();
        calls.set(0);
        mode = Mode.SUCCESS;
        errorStatus = 503;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setErrorStatus(int status) {
        errorStatus = status;
        mode = Mode.HTTP_ERROR;
    }

    public CapturedRequest takeRequest() throws InterruptedException {
        CapturedRequest request = requests.poll(5, TimeUnit.SECONDS);
        if (request == null) {
            throw new IllegalStateException("Feign did not reach Channel within five seconds");
        }
        return request;
    }

    public boolean hasChannel(UUID streamerId) {
        return channels.contains(streamerId);
    }

    public int callCount() {
        return calls.get();
    }

    public void releaseResponse() {
        releaseResponse.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Mode requestMode = mode;
        CountDownLatch requestRelease = releaseResponse;
        calls.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), body));
        try {
            if (requestMode == Mode.HTTP_ERROR) {
                send(exchange, errorStatus, "{\"status\":" + errorStatus
                        + ",\"code\":1003,\"message\":\"Channel data unavailable\",\"result\":false}");
                return;
            }
            if (requestMode == Mode.SUCCESS || requestMode == Mode.LOST_RESPONSE || requestMode == Mode.INVALID_JSON) {
                if ("PATCH".equals(exchange.getRequestMethod())) {
                    String path = exchange.getRequestURI().getPath();
                    String streamerId = path.substring("/channel/internal/api/channels/by-streamer/".length(),
                            path.length() - "/avatar".length());
                    if (!channels.contains(UUID.fromString(streamerId))) {
                        send(exchange, 404, "{\"status\":404,\"code\":1009,\"message\":\"Channel not found\"}");
                        return;
                    }
                } else {
                    channels.add(UUID.fromString(mapper.readTree(body).path("streamerId").asString()));
                }
            }
            switch (requestMode) {
                case LOST_RESPONSE -> {
                    try {
                        requestRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                case INVALID_JSON -> send(exchange, 200, "{invalid-json");
                case EMPTY_BODY -> exchange.sendResponseHeaders(200, -1);
                case FALSE_RESULT -> send(exchange, 200, envelope("false"));
                case NULL_RESULT -> send(exchange, 200, envelope("null"));
                default -> send(exchange, 200, envelope("true"));
            }
        } finally {
            exchange.close();
        }
    }

    private String envelope(String result) {
        return "{\"status\":200,\"code\":1000,\"message\":\"Channel created successfully\",\"result\":"
                + result + ",\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    public record CapturedRequest(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }
}
