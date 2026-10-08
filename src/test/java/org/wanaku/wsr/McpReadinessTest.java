package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the maintained SDK against real local JSON and Streamable HTTP SSE responses. */
@Timeout(15)
class McpReadinessTest {
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesDiscoversAndDeletesSessionWithoutInvokingTools(boolean sse) throws Exception {
        try (var server = new Fixture(sse)) {
            McpReadiness.verify(server.endpoint(), "route_support", EXCHANGE_TIMEOUT, CLEANUP_TIMEOUT);
            assertEquals(1, server.initializations.get());
            assertEquals(1, server.notifications.get());
            assertEquals(1, server.discoveries.get());
            assertEquals(1, server.deletions.get());
            assertEquals(0, server.invocations.get());
            assertEquals(0, server.unexpected.get(), "Unexpected MCP method: " + server.unexpectedMethod);
            assertTrue(server.protocolHeadersValid.get());
        }
    }

    @Test
    void rejectsMissingDifferentAndAdditionalToolsAndStillCleansTheSession() throws Exception {
        for (List<String> names : List.of(List.<String>of(), List.of("different"), List.of("route_support", "extra"))) {
            try (var server = new Fixture(false)) {
                server.names = names;
                assertThrows(
                        IOException.class,
                        () -> McpReadiness.verify(
                                server.endpoint(), "route_support", EXCHANGE_TIMEOUT, CLEANUP_TIMEOUT));
                assertEquals(1, server.deletions.get());
                assertEquals(0, server.invocations.get());
                assertEquals(0, server.unexpected.get(), "Unexpected MCP method: " + server.unexpectedMethod);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discoveryErrorsRemainPrimaryWhenCleanupFails(boolean cleanupFailure) throws Exception {
        try (var server = new Fixture(false)) {
            server.discoveryError = true;
            server.deleteStatus = cleanupFailure ? 500 : 204;
            var failure = assertThrows(
                    IOException.class,
                    () -> McpReadiness.verify(server.endpoint(), "route_support", EXCHANGE_TIMEOUT, CLEANUP_TIMEOUT));
            assertEquals("MCP readiness failed", failure.getMessage());
            assertFalse(failure.getMessage().contains("secret-fixture-value"));
            assertEquals(cleanupFailure ? 1 : 0, failure.getSuppressed().length);
            if (cleanupFailure) {
                assertEquals("MCP readiness cleanup failed", failure.getSuppressed()[0].getMessage());
            }
            assertEquals(1, server.deletions.get());
            assertEquals(0, server.invocations.get());
            assertEquals(0, server.unexpected.get(), "Unexpected MCP method: " + server.unexpectedMethod);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"initialize", "tools/list"})
    void incompleteResponseBodiesHaveABoundedReadinessDeadlineAndSessionCleanup(String method) throws Exception {
        try (var server = new Fixture(false)) {
            server.pauseMethod = method;
            assertTimeout(
                    Duration.ofSeconds(4),
                    () -> assertThrows(
                            IOException.class,
                            () -> McpReadiness.verify(
                                    server.endpoint(), "route_support", Duration.ofSeconds(1), CLEANUP_TIMEOUT)));
            assertEquals(1, server.deletions.get());
            assertEquals(0, server.invocations.get());
            assertEquals(0, server.unexpected.get(), "Unexpected MCP method: " + server.unexpectedMethod);
        }
    }

    @Test
    void incompleteDeleteResponseCannotBlockReadinessCleanup() throws Exception {
        try (var server = new Fixture(false)) {
            server.pauseMethod = "delete";
            assertTimeout(
                    Duration.ofSeconds(4),
                    () -> assertThrows(
                            IOException.class,
                            () -> McpReadiness.verify(
                                    server.endpoint(), "route_support", EXCHANGE_TIMEOUT, Duration.ofMillis(300))));
            assertEquals(1, server.deletions.get());
            assertEquals(0, server.invocations.get());
            assertEquals(0, server.unexpected.get(), "Unexpected MCP method: " + server.unexpectedMethod);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean sse;
        private final AtomicInteger initializations = new AtomicInteger();
        private final AtomicInteger notifications = new AtomicInteger();
        private final AtomicInteger discoveries = new AtomicInteger();
        private final AtomicInteger deletions = new AtomicInteger();
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger unexpected = new AtomicInteger();
        private final AtomicBoolean protocolHeadersValid = new AtomicBoolean(true);
        private volatile List<String> names = List.of("route_support");
        private volatile String pauseMethod;
        private volatile boolean discoveryError;
        private volatile int deleteStatus = 204;
        private volatile String unexpectedMethod;

        Fixture(boolean sse) throws IOException {
            this.sse = sse;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!McpReadiness.PROTOCOL_VERSION.equals(
                        exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))) {
                    protocolHeadersValid.set(false);
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    deletions.incrementAndGet();
                    if ("delete".equals(pauseMethod)) {
                        pause(exchange);
                    } else {
                        exchange.sendResponseHeaders(deleteStatus, -1);
                    }
                    return;
                }
                var request = JSON.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if ("initialize".equals(method)) {
                    initializations.incrementAndGet();
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "readiness-session");
                }
                if (method.equals(pauseMethod)) {
                    pause(exchange);
                    return;
                }
                if ("notifications/initialized".equals(method)) {
                    notifications.incrementAndGet();
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                if ("notifications/cancelled".equals(method)) {
                    if (request.has("id")
                            || !request.path("params").path("requestId").isIntegralNumber()) {
                        unexpected.incrementAndGet();
                        unexpectedMethod = "malformed cancellation notification";
                        exchange.sendResponseHeaders(400, -1);
                    } else {
                        exchange.sendResponseHeaders(202, -1);
                    }
                    return;
                }
                Map<String, Object> result;
                if ("initialize".equals(method)) {
                    result = Map.of(
                            "protocolVersion",
                                    request.path("params")
                                            .path("protocolVersion")
                                            .asText(),
                            "capabilities", Map.of("tools", Map.of("listChanged", false)),
                            "serverInfo", Map.of("name", "fixture", "version", "1"));
                } else if ("tools/list".equals(method)) {
                    discoveries.incrementAndGet();
                    if (discoveryError) {
                        respond(
                                exchange,
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        request.get("id"),
                                        "error",
                                        Map.of("code", -32603, "message", "secret-fixture-value")));
                        return;
                    }
                    List<Map<String, Object>> tools = new ArrayList<>();
                    for (String name : names) {
                        tools.add(Map.of(
                                "name",
                                name,
                                "description",
                                "Fixture tool",
                                "inputSchema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("message", Map.of("type", "string")),
                                        "required",
                                        List.of("message"))));
                    }
                    result = Map.of("tools", tools);
                } else if ("ping".equals(method)) {
                    result = Map.of();
                } else {
                    if ("tools/call".equals(method)) {
                        invocations.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                        unexpectedMethod = method;
                    }
                    if (request.has("id")) {
                        respond(
                                exchange,
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        request.get("id"),
                                        "error",
                                        Map.of("code", -32601, "message", "Method not found")));
                    } else {
                        exchange.sendResponseHeaders(400, -1);
                    }
                    return;
                }
                respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            }
        }

        private void respond(HttpExchange exchange, Map<String, Object> response) throws IOException {
            String json = JSON.writeValueAsString(response);
            byte[] bytes = (sse ? ": heartbeat\n\nevent: message\ndata: " + json + "\n\n" : json)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private void pause(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"jsonrpc\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
