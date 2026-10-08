package com.reallive.userservice.common.configuration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R2ConfigTest {

    @Test
    void signsPathStyleUploadsWithoutAwsChunkedEncoding() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentEncoding = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploaded.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] content = "test image content".getBytes(StandardCharsets.UTF_8);
        try (var client = new R2Config().r2Client(endpoint, "test-access-key", "test-secret-key")) {
            client.putObject(PutObjectRequest.builder()
                    .bucket("test-avatars")
                    .key("avatars/avatar.png")
                    .contentType("image/png")
                    .build(), RequestBody.fromBytes(content));
        } finally {
            server.stop(0);
        }

        assertEquals("/test-avatars/avatars/avatar.png", path.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=test-access-key/"));
        assertTrue(authorization.get().contains("/auto/s3/aws4_request"));
        assertFalse(contentEncoding.get() != null && contentEncoding.get().contains("aws-chunked"));
        assertEquals("image/png", contentType.get());
        assertArrayEquals(content, uploaded.get());
    }
}
