package com.reallive.userservice.client.wallet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP stub for testing the real Feign transport without a running Payment deployment. */
public class PaymentHttpTestServer implements AutoCloseable {

    public enum Mode { SUCCESS, HTTP_ERROR, INVALID_JSON, EMPTY_BODY, LOST_RESPONSE }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private final Map<UUID, UUID> wallets = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.SUCCESS;
    private volatile int errorStatus = 503;
    private volatile CountDownLatch releaseResponse = new CountDownLatch(1);

    public PaymentHttpTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/payment/internal/api/wallets/create/", this::handle);
            server.start();
        } catch (IOException exception) {
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start Payment HTTP test server", exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/payment";
    }

    public void reset() {
        releaseResponse.countDown();
        releaseResponse = new CountDownLatch(1);
        requests.clear();
        wallets.clear();
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
            throw new IllegalStateException("Feign did not reach Payment within five seconds");
        }
        return request;
    }

    public boolean hasWallet(UUID userId) {
        return wallets.containsKey(userId);
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
        String path = exchange.getRequestURI().getPath();
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders())));
        UUID userId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        try {
            if (requestMode == Mode.HTTP_ERROR) {
                send(exchange, errorStatus, "{\"status\":" + errorStatus + ",\"code\":1003,\"message\":\"Payment unavailable\",\"result\":null}");
                return;
            }
            UUID walletId = wallets.computeIfAbsent(userId, ignored -> UUID.randomUUID());
            if (requestMode == Mode.LOST_RESPONSE) {
                try {
                    requestRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (requestMode == Mode.INVALID_JSON) {
                send(exchange, 200, "{invalid-json");
            } else if (requestMode == Mode.EMPTY_BODY) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                send(exchange, 200, "{\"status\":200,\"code\":0,\"message\":\"Wallet created successfully\","
                        + "\"result\":{\"id\":\"" + walletId + "\",\"userId\":\"" + userId + "\",\"balance\":0},"
                        + "\"timestamp\":\"2026-01-01T00:00:00Z\"}");
            }
        } finally {
            exchange.close();
        }
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

    public record CapturedRequest(String method, String path, Map<String, List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }
}
