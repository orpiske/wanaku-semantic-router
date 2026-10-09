package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the packaged JAR with its manifest classpath rather than the test classloader. */
class PackagedRuntimeIT {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "packaged runtime publication discovery = {0}")
    @ValueSource(booleans = {false, true})
    @Timeout(45)
    void packagedRuntimeLoadsCatalogClassifiesRegistersAndDeregisters(boolean discovery) throws Exception {
        byte[] archive = RuntimeTest.archive(RuntimeTest.reference());
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        control.setExecutor(executor);
        int port = control.getAddress().getPort();
        AtomicInteger registered = new AtomicInteger(), deleted = new AtomicInteger(), resolved = new AtomicInteger();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> forward = new AtomicReference<>();
        AtomicReference<String> downloadQuery = new AtomicReference<>(), downloadAuth = new AtomicReference<>();
        AtomicReference<String> resolveQuery = new AtomicReference<>(), resolveAuth = new AtomicReference<>();
        var publication = SemanticRouteTest.publication();
        publication.put("catalogName", "semantic-support-spike");
        publication.put("revision", "1");
        publication.put(
                "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
        publication.put("downloadUrl", "/api/v1/service-catalog/download?name=semantic-support-spike");
        ((com.fasterxml.jackson.databind.node.ObjectNode) publication.get("expert")).put("bean", "supportExpert");
        control.createContext("/api/v1/semantic-routers/resolve", exchange -> {
            resolved.incrementAndGet();
            resolveQuery.set(java.net.URLDecoder.decode(
                    exchange.getRequestURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8));
            resolveAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            PreviewService.respond(exchange, 200, Map.of("data", publication));
        });
        Map<String, String> managementAuth = new ConcurrentHashMap<>();
        control.createContext("/api/v1/service-catalog/download", exchange -> {
            downloadQuery.set(exchange.getRequestURI().getRawQuery());
            downloadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            PreviewService.respond(
                    exchange,
                    200,
                    Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive))));
        });
        control.createContext("/api/v1/forwards", exchange -> {
            managementAuth.put(
                    exchange.getRequestMethod(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (exchange.getRequestMethod().equals("POST")) {
                forward.set(RuntimeTest.JSON.readTree(exchange.getRequestBody()));
                try {
                    McpReadiness.verify(URI.create(forward.get().path("address").asText()), "route_support");
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                registered.incrementAndGet();
                PreviewService.respond(
                        exchange,
                        200,
                        Map.of("data", Map.of("forward", Map.of("available", true), "tools_discovered", 1)));
            } else if (exchange.getRequestMethod().equals("GET")) {
                PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
            } else {
                deleted.incrementAndGet();
                PreviewService.respond(exchange, 200, Map.of("removed", "reference"));
            }
        });
        control.start();
        Process process = null;
        try (var provider = new LocalProvider(0)) {
            int mcp = RuntimeTest.freePort(), status = RuntimeTest.freePort();
            RuntimeTest helper = new RuntimeTest();
            helper.directory = directory;
            var config = helper.settings(archive, port, port, mcp, provider.port());
            List<String> arguments = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-jar",
                    System.getProperty("packagedJar"),
                    "runtime",
                    "--barn-url",
                    config.getProperty("wsr.barn.url"),
                    "--registration-url",
                    config.getProperty("wsr.wanaku.url"),
                    "--service-catalog",
                    config.getProperty("wsr.catalog.name"),
                    "--service-catalog-system",
                    "support",
                    "--catalog-revision",
                    "1",
                    "--catalog-sha256",
                    config.getProperty("wsr.catalog.sha256"),
                    "--catalog-main",
                    "support/support.camel.yaml",
                    "--name",
                    "reference",
                    "--namespace",
                    "team",
                    "--mcp-address",
                    config.getProperty("wsr.mcp.address"),
                    "--mcp-port",
                    Integer.toString(mcp),
                    "--bind",
                    "127.0.0.1",
                    "--status-port",
                    Integer.toString(status),
                    "--status-bind",
                    "127.0.0.1",
                    "--http-timeout-ms",
                    "10000",
                    "--work-directory",
                    directory.toString(),
                    "--barn-token-env",
                    "WSR_TEST_BARN_TOKEN",
                    "--wanaku-token-env",
                    "WSR_TEST_WANAKU_TOKEN",
                    "--expert",
                    "supportExpert=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter"));
            for (String key : config.stringPropertyNames().stream()
                    .filter(key -> key.startsWith("camel."))
                    .sorted()
                    .toList()) {
                arguments.addAll(List.of("--property", key + "=" + config.getProperty(key)));
            }
            if (discovery) {
                arguments = new ArrayList<>(List.of(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-jar",
                        System.getProperty("packagedJar"),
                        "runtime",
                        "--semantic-route",
                        SemanticRouteTest.ROUTE,
                        "-p",
                        "camel.component.typesafe-ai.base-url=http://127.0.0.1:" + provider.port()));
            }
            Path log = directory.resolve("runtime.log");
            var builder =
                    new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("WSR_") || key.startsWith("TYPESAFE_"));
            if (discovery) {
                builder.environment()
                        .putAll(Map.ofEntries(
                                Map.entry("WSR_BARN_URL", config.getProperty("wsr.barn.url")),
                                Map.entry("WSR_REGISTRATION_URL", config.getProperty("wsr.wanaku.url")),
                                Map.entry("WSR_MCP_PORT", Integer.toString(mcp)),
                                Map.entry("WSR_STATUS_PORT", Integer.toString(status)),
                                Map.entry("WSR_WORK_DIRECTORY", directory.toString()),
                                Map.entry("WSR_BARN_TOKEN_ENV", "WSR_TEST_BARN_TOKEN"),
                                Map.entry("WSR_WANAKU_TOKEN_ENV", "WSR_TEST_WANAKU_TOKEN"),
                                Map.entry("WSR_NAMESPACE", "team"),
                                Map.entry("TYPESAFE_API_KEY", "classification-fixture-key"),
                                Map.entry("TYPESAFE_MODEL", "fixture")));
            }
            builder.environment().put("WSR_TEST_BARN_TOKEN", "catalog-fixture-token");
            builder.environment().put("WSR_TEST_WANAKU_TOKEN", "management-fixture-token");
            process = builder.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            String state = null;
            var http = HttpClient.newHttpClient();
            while (System.nanoTime() < end && process.isAlive()) {
                try {
                    var response = http.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + status + "/api/v1/status"))
                                    .timeout(java.time.Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    state = RuntimeTest.JSON
                            .readTree(response.body())
                            .path("state")
                            .asText();
                    if (state.equals("ready")) {
                        break;
                    }
                } catch (java.io.IOException ignored) {
                }
                Thread.sleep(50);
            }
            assertEquals("ready", state, Files.readString(log));
            assertEquals("name=semantic-support-spike", downloadQuery.get());
            assertEquals("Bearer catalog-fixture-token", downloadAuth.get());
            assertEquals(1, registered.get());
            assertEquals(
                    discovery ? "route_support" : "reference",
                    forward.get().path("name").asText());
            assertEquals(discovery ? 1 : 0, resolved.get());
            if (discovery) {
                assertEquals("name=" + SemanticRouteTest.ROUTE, resolveQuery.get());
                assertEquals("Bearer catalog-fixture-token", resolveAuth.get());
            }
            assertEquals("team", forward.get().path("namespace").asText());
            assertEquals(
                    config.getProperty("wsr.mcp.address"),
                    forward.get().path("address").asText());
            assertEquals("1", forward.get().path("labels").path("wsr.revision").asText());
            assertEquals(
                    config.getProperty("wsr.catalog.sha256"),
                    forward.get().path("labels").path("wsr.digest").asText());
            assertEquals("Bearer management-fixture-token", managementAuth.get("POST"));
            McpReadiness.verify(URI.create("http://127.0.0.1:" + mcp + "/mcp"), "route_support");
            if (discovery) {
                var transport = new StreamableHttpMcpTransport.Builder()
                        .url(config.getProperty("wsr.mcp.address"))
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
                    var result = client.executeTool(ToolExecutionRequest.builder()
                            .name("route_support")
                            .arguments(RuntimeTest.JSON.writeValueAsString(Map.of("message", "billing")))
                            .build());
                    assertFalse(result.isError(), result.resultText());
                    assertTrue(result.resultText().contains("Billing demo response"), result.resultText());
                    assertEquals(1, provider.evaluations.get());
                }
                assertEquals(1, resolved.get(), "Tool calls must retain the startup publication selection");
            }
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), Files.readString(log));
            assertEquals(1, deleted.get(), Files.readString(log));
            assertEquals("Bearer management-fixture-token", managementAuth.get("GET"));
            assertEquals("Bearer management-fixture-token", managementAuth.get("DELETE"));
            assertFalse(Files.exists(directory.resolve("runtime.properties")));
            assertFalse(Files.readString(log).contains("catalog-fixture-token"));
            assertFalse(Files.readString(log).contains("management-fixture-token"));
            assertFalse(Files.readString(log).contains("classification-fixture-key"));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            control.stop(0);
            executor.shutdownNow();
        }
    }
}
