package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.semantic.SemanticAuditRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

class WanakuAuditSinkTest {
    private static final String ACK =
            "{\"data\":{\"status\":\"accepted_in_memory\",\"event_id\":\"stored-id\",\"duplicate\":false},\"error\":null}";
    private static final Map<String, String> SOURCE = Map.of(
            "instance_id",
            "instance",
            "forward_name",
            "support",
            "namespace",
            "default",
            "catalog_revision",
            "r1",
            "catalog_digest",
            "a".repeat(64));

    @Test
    void forwardsTypedResultsCategoriesAndEvidenceWithoutInputOrCredentials() throws Exception {
        var bodies = new CopyOnWriteArrayList<byte[]>();
        HttpServer server = server(exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, ACK);
        });
        try {
            var sink = sink(server);
            var values = List.of(true, "billing", 42, List.of("billing", "technical"));
            for (Object value : values) {
                var fields = record("evaluation", "event-" + bodies.size());
                fields.put("result", Map.of("value", value));
                fields.put("input", Map.of("secret", "never forward"));
                fields.put("inputRedacted", false);
                sink.append(SemanticAuditRecord.fromMap(fields));
            }
            for (String category : List.of("decision", "request")) {
                sink.append(SemanticAuditRecord.fromMap(record(category, category)));
            }
            var mapper = new ObjectMapper();
            for (int i = 0; i < bodies.size(); i++) {
                var envelope = mapper.readTree(bodies.get(i));
                assertEquals(mapper.valueToTree(SOURCE), envelope.get("source"));
                var record = envelope.get("record");
                assertEquals("evidence-id", record.path("evidence").get(0).asText());
                assertFalse(record.has("input"));
                assertFalse(record.has("inputRedacted"));
                if (i < values.size()) {
                    assertEquals(
                            mapper.valueToTree(values.get(i)),
                            record.path("result").get("value"));
                    assertEquals(
                            "forwarding_policy", record.path("inputOmitted").asText());
                }
            }
            assertEquals(6L, sink.status().get("delivered"));
            sink.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesIdenticalBytesAndAcceptsDuplicateAcknowledgement() throws Exception {
        var bodies = new CopyOnWriteArrayList<byte[]>();
        HttpServer server = server(exchange -> {
            bodies.add(exchange.getRequestBody().readAllBytes());
            respond(exchange, bodies.size() < 3 ? 503 : 200, ACK.replace("false", "true"));
        });
        try {
            var sink = sink(server);
            sink.append(SemanticAuditRecord.fromMap(record("request", "id")));
            assertEquals(3, bodies.size());
            assertArrayEquals(bodies.get(0), bodies.get(1));
            assertArrayEquals(bodies.get(0), bodies.get(2));
            assertEquals(2L, sink.status().get("retries"));
            assertEquals(1L, sink.status().get("delivered"));
            sink.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPermanentFailuresAndMalformedAcknowledgementsWithoutRetry() throws Exception {
        for (int status : List.of(400, 401, 409, 302, 200)) {
            var calls = new AtomicInteger();
            HttpServer server = server(exchange -> {
                calls.incrementAndGet();
                respond(exchange, status, "secret response");
            });
            try {
                var sink = sink(server);
                var error = assertThrows(
                        IOException.class, () -> sink.append(SemanticAuditRecord.fromMap(record("request", "id"))));
                assertEquals(status == 200 ? "invalid_acknowledgement" : "receiver_rejected", error.getMessage());
                assertNull(error.getCause());
                assertEquals(1, calls.get());
                assertEquals(1L, sink.status().get("failed"));
                sink.stop();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void boundsRequestBeforeSendingAndResponseDuringReceipt() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "x".repeat(8193));
        });
        try {
            var sink =
                    new WanakuAuditSink(new AuditSettings(true, url(server)), Map.of("instance_id", "x".repeat(65536)));
            sink.start();
            var oversized = sink;
            assertEquals(
                    "request_too_large",
                    assertThrows(
                                    IOException.class,
                                    () -> oversized.append(SemanticAuditRecord.fromMap(record("request", "id"))))
                            .getMessage());
            assertEquals(0, calls.get());
            sink.stop();
            sink = sink(server);
            var bounded = sink;
            assertThrows(IOException.class, () -> bounded.append(SemanticAuditRecord.fromMap(record("request", "id"))));
            assertEquals(3, calls.get());
            assertEquals(1L, sink.status().get("failed"));
            sink.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutEachAttemptIncludingResponseBodyAndBoundsRetries() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(' ');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(1150);
                exchange.getResponseBody().write(ACK.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expected) {
                // Completion timeout closes the unfinished body.
            } finally {
                exchange.close();
            }
        });
        try {
            var sink = sink(server);
            long start = System.nanoTime();
            assertEquals(
                    "transport_failed",
                    assertThrows(
                                    IOException.class,
                                    () -> sink.append(SemanticAuditRecord.fromMap(record("request", "id"))))
                            .getMessage());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs >= 3000 && elapsedMs < 6000, "elapsed milliseconds: " + elapsedMs);
            assertEquals(3, calls.get());
            assertEquals(2L, sink.status().get("retries"));
            sink.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledStoppedAndInterruptedSinksDoNotSend() throws Exception {
        var record = SemanticAuditRecord.fromMap(record("request", "id"));
        var disabled = new WanakuAuditSink(new AuditSettings(false, URI.create("http://127.0.0.1:1")), SOURCE);
        disabled.append(record);
        assertEquals(0L, disabled.status().get("received"));
        var sink = new WanakuAuditSink(new AuditSettings(true, URI.create("http://127.0.0.1:1")), SOURCE);
        assertEquals(
                "sink_stopped",
                assertThrows(IOException.class, () -> sink.append(record)).getMessage());
        sink.start();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> sink.append(record));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            sink.stop();
        }
    }

    private static Map<String, Object> record(String category, String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("schemaVersion", 1);
        map.put("eventId", id);
        map.put("category", category);
        map.put("timestamp", "2026-10-12T12:00:00Z");
        map.put("evidence", List.of("evidence-id"));
        return map;
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1/audit/semantic", handler);
        server.start();
        return server;
    }

    private static URI url(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/audit/semantic");
    }

    private static WanakuAuditSink sink(HttpServer server) throws Exception {
        var sink = new WanakuAuditSink(new AuditSettings(true, url(server)), SOURCE);
        sink.start();
        return sink;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
