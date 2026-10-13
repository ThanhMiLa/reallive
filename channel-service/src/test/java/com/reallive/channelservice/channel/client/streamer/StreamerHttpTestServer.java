package com.reallive.channelservice.channel.client.streamer;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamerHttpTestServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<String> requests = new ConcurrentLinkedQueue<>();
    private volatile int status = 200;
    private volatile String body;
    private volatile long delayMillis;

    public StreamerHttpTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/user/internal/api/streamers/by-user/", exchange -> {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                int responseStatus = "GET".equals(exchange.getRequestMethod()) ? status : 405;
                String responseBody = body;
                long responseDelay = delayMillis;
                try (exchange) {
                    if (responseDelay > 0) {
                        Thread.sleep(responseDelay);
                    }
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
        } catch (IOException exception) {
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start Streamer HTTP test server", exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/user";
    }

    public void reset(UUID streamerId) {
        status = 200;
        body = "{\"status\":200,\"code\":1000,\"result\":{\"streamerId\":\"" + streamerId + "\"}}";
        delayMillis = 0;
        requests.clear();
    }

    public void reply(int responseStatus, String responseBody) {
        status = responseStatus;
        body = responseBody;
    }

    public void delay(long milliseconds) {
        delayMillis = milliseconds;
    }

    public Queue<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
