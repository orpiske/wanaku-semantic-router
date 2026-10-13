package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void capturesWithoutCatalogAuditAndKeepsRoutingWhenReceiverFails() throws Exception {
        exercise("", null);
    }

    @Test
    void disabledRuntimeOverridesEnabledCatalogCapture() throws Exception {
        exercise(
                """
                audit:
                  enabled: true
                  experts:
                    supportExpert:
                      enabled: true
                  sinks: [memory, wanakuAudit]
                  reader: memory
                """,
                false);
    }

    @Test
    void enabledRuntimeOverridesCatalogExpertInputAndSinkSettings() throws Exception {
        exercise(
                """
                audit:
                  enabled: false
                  experts:
                    supportExpert:
                      enabled: false
                      input:
                        enabled: true
                  sinks: [memory, catalogOnlySink]
                  reader: memory
                  capacity: 1
                  queueCapacity: 1
                """,
                true);
    }

    @Test
    void localFilesCaptureAuditWithSnapshotMetadataWithoutBarn() throws Exception {
        exercise("", null, true);
    }

    private void exercise(String catalogAudit, Boolean enabled) throws Exception {
        exercise(catalogAudit, enabled, false);
    }

    private void exercise(String catalogAudit, Boolean enabled, boolean localFiles) throws Exception {
        Map<String, byte[]> entries = RuntimeTest.reference();
        String yaml = new String(entries.get("support/support.camel.yaml"), StandardCharsets.UTF_8);
        yaml = yaml.replace(
                "- semantic:\n",
                """
                - beans:
                  - name: recordContinuation
                    type: org.apache.camel.semantic.SemanticAuditDecision
                    properties:
                      fields:
                        action: continue
                        operation: tools/call
                        target: route_support
                        reasonCode: classified
                - semantic:
                """
                        + catalogAudit.indent(4));
        yaml = yaml.replace(
                "        - to: direct:classify-support\n",
                """
                        - to: direct:classify-support
                        - process:
                            ref: recordContinuation
                """);
        entries.put("support/support.camel.yaml", yaml.getBytes(StandardCharsets.UTF_8));
        byte[] archive = RuntimeTest.archive(entries);
        var records = new CopyOnWriteArrayList<JsonNode>();
        var receiverStatus = new AtomicInteger(200);
        var downloads = new AtomicInteger();
        var forward = new AtomicReference<JsonNode>();
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        control.setExecutor(executor);
        control.createContext("/api/v1/service-catalog/download", exchange -> {
            downloads.incrementAndGet();
            PreviewService.respond(
                    exchange,
                    200,
                    Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive))));
        });
        control.createContext("/api/v1/forwards", exchange -> {
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    forward.set(RuntimeTest.JSON.readTree(exchange.getRequestBody()));
                    PreviewService.respond(
                            exchange,
                            200,
                            Map.of("data", Map.of("forward", Map.of("available", true), "tools_discovered", 1)));
                }
                case "GET" -> PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
                default -> PreviewService.respond(exchange, 200, Map.of("data", Map.of()));
            }
        });
        control.createContext("/api/v1/audit/semantic", exchange -> {
            assertFalse(exchange.getRequestHeaders().containsKey("Authorization"));
            records.add(RuntimeTest.JSON.readTree(exchange.getRequestBody()));
            var acknowledgement = RuntimeTest.JSON.createObjectNode();
            acknowledgement.putNull("error");
            acknowledgement.set(
                    "data",
                    RuntimeTest.JSON.valueToTree(
                            Map.of("status", "accepted_in_memory", "event_id", "stored-id", "duplicate", false)));
            PreviewService.respond(exchange, receiverStatus.get(), acknowledgement);
        });
        control.start();
        try (var provider = new LocalProvider(0)) {
            int port = control.getAddress().getPort();
            int mcp = RuntimeTest.freePort();
            var fixture = new RuntimeTest();
            fixture.directory = directory;
            var deployment = fixture.settings(archive, port, port, mcp, provider.port());
            if (localFiles) {
                Path source = directory.resolve("source");
                for (var entry : entries.entrySet()) {
                    Path file = source.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
                deployment
                        .keySet()
                        .removeIf(key -> key.toString().startsWith("wsr.catalog.")
                                || key.toString().startsWith("wsr.barn."));
                deployment.setProperty(
                        "wsr.routes-ref",
                        source.resolve("support/support.camel.yaml").toUri().toString());
            }
            if (enabled != null) {
                deployment.setProperty("wsr.audit.enabled", enabled.toString());
            }
            try (var runtime = new SemanticRuntime(RuntimeSettings.from(deployment))) {
                runtime.start();
                var capture = (Map<?, ?>) ((Map<?, ?>) runtime.status().get("audit")).get("capture");
                boolean auditing = !Boolean.FALSE.equals(enabled);
                assertEquals(auditing, capture.get("enabled"));
                assertEquals(auditing, capture.get("decisionsEnabled"));
                assertEquals(Map.of(), capture.get("experts"));
                assertEquals(Map.of(), capture.get("inputs"));
                assertEquals(1000, capture.get("capacity"));
                assertEquals(auditing ? List.of("memory", "wanakuAudit") : List.of("memory"), capture.get("sinks"));
                var transport = new StreamableHttpMcpTransport.Builder()
                        .url("http://127.0.0.1:" + mcp + "/mcp")
                        .customHeaders(Map.of("MCP-Protocol-Version", McpReadiness.PROTOCOL_VERSION))
                        .timeout(Duration.ofSeconds(10))
                        .logRequests(false)
                        .logResponses(false)
                        .build();
                try (var client = new DefaultMcpClient.Builder()
                        .transport(transport)
                        .protocolVersion(McpReadiness.PROTOCOL_VERSION)
                        .initializationTimeout(Duration.ofSeconds(10))
                        .toolExecutionTimeout(Duration.ofSeconds(10))
                        .autoHealthCheck(false)
                        .build()) {
                    assertEquals("route_support", client.listTools().get(0).name());
                    var request = ToolExecutionRequest.builder()
                            .name("route_support")
                            .arguments("{\"message\":\"billing\"}")
                            .build();
                    assertTrue(client.executeTool(request).resultText().contains("Billing demo response"));
                    if (auditing) {
                        await(runtime, "delivered", 2);
                        assertEquals(2, records.size());
                        var evaluation = records.stream()
                                .filter(event -> event.path("record")
                                        .path("category")
                                        .asText()
                                        .equals("evaluation"))
                                .findFirst()
                                .orElseThrow();
                        var decision = records.stream()
                                .filter(event -> event.path("record")
                                        .path("category")
                                        .asText()
                                        .equals("decision"))
                                .findFirst()
                                .orElseThrow();
                        assertEquals(
                                "billing",
                                evaluation
                                        .path("record")
                                        .path("result")
                                        .path("value")
                                        .asText());
                        assertEquals(
                                evaluation.path("record").path("eventId").asText(),
                                decision.path("record").path("evidence").get(0).asText());
                        assertEquals(
                                "continue",
                                decision.path("record").path("action").asText());
                        assertEquals(
                                "reference",
                                evaluation.path("source").path("forward_name").asText());
                        assertEquals(
                                forward.get()
                                        .path("labels")
                                        .path("wsr.instance")
                                        .asText(),
                                evaluation.path("source").path("instance_id").asText());
                        assertEquals(
                                localFiles ? "local" : "1",
                                evaluation
                                        .path("source")
                                        .path("catalog_revision")
                                        .asText());
                        String digest =
                                evaluation.path("source").path("catalog_digest").asText();
                        assertTrue(digest.matches("[0-9a-f]{64}"));
                        assertEquals(
                                forward.get().path("labels").path("wsr.digest").asText(), digest);
                        assertEquals(
                                forward.get()
                                        .path("labels")
                                        .path("wsr.revision")
                                        .asText(),
                                evaluation
                                        .path("source")
                                        .path("catalog_revision")
                                        .asText());
                        assertFalse(evaluation.path("record").has("input"));
                        receiverStatus.set(503);
                        assertTrue(client.executeTool(request).resultText().contains("Billing demo response"));
                        await(runtime, "failed", 2);
                        assertEquals("ready", runtime.status().get("state"));
                        var audit = (Map<?, ?>) runtime.status().get("audit");
                        assertEquals(0L, ((Map<?, ?>) audit.get("capture")).get("dropped"));
                    }
                }
            }
            assertEquals(localFiles ? 0 : 1, downloads.get());
            if (Boolean.FALSE.equals(enabled)) {
                assertEquals(List.of(), records);
            }
        } finally {
            control.stop(0);
            executor.shutdownNow();
        }
    }

    private static void await(SemanticRuntime runtime, String counter, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var audit = (Map<?, ?>) runtime.status().get("audit");
            if (((Number) audit.get(counter)).longValue() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, ((Map<?, ?>) runtime.status().get("audit")).get(counter));
    }
}
