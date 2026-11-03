package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeTest {
    @TempDir
    Path directory;

    static final ObjectMapper JSON = new ObjectMapper();

    static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static byte[] archive(Map<String, byte[]> contents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static Map<String, byte[]> reference() throws Exception {
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        Path root = Path.of("src/test/resources/catalog");
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                entries.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return entries;
    }

    static void writeLocalSources(Path root) throws Exception {
        for (var entry : reference().entrySet()) {
            if (!entry.getKey().equals("index.properties")) {
                Path file = root.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
        }
    }

    /** Test-scoped status listener that serves the same handlers as the runtime command. */
    record StatusServer(HttpServer server) implements AutoCloseable {
        static StatusServer start(SemanticRuntime runtime) throws Exception {
            HttpServer server = RuntimeCommand.createStatusServer(runtime, "127.0.0.1", 0);
            server.start();
            return new StatusServer(server);
        }

        int probe(String method, String path) throws Exception {
            return HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                            + server.getAddress().getPort() + path))
                                    .timeout(Duration.ofSeconds(5))
                                    .method(method, HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }

        /** Returns the live, ready and started probe codes in that order. */
        List<Integer> probes() throws Exception {
            return List.of(
                    probe("GET", "/api/v1/health/live"),
                    probe("GET", "/api/v1/health/ready"),
                    probe("GET", "/api/v1/health/started"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    Properties settings(byte[] archive, int barnPort, int wanakuPort, int mcpPort, int providerPort) throws Exception {
        Properties p = PreviewServiceTest.providerSettings(providerPort);
        p.setProperty("wsr.experts", "supportExpert");
        p.setProperty(
                "wsr.expert.supportExpert.class", "org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter");
        p.setProperty("wsr.barn.url", "http://127.0.0.1:" + barnPort);
        p.setProperty("wsr.catalog.name", "semantic-support-spike");
        p.setProperty("wsr.catalog.service", "support");
        p.setProperty("wsr.catalog.revision", "1");
        p.setProperty(
                "wsr.catalog.sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
        p.setProperty("wsr.wanaku.url", "http://127.0.0.1:" + wanakuPort);
        p.setProperty("wsr.forward.name", "reference");
        p.setProperty("wsr.mcp.address", "http://127.0.0.1:" + mcpPort + "/mcp");
        p.setProperty("wsr.port", Integer.toString(mcpPort));
        p.setProperty("wsr.work-directory", directory.toString());
        return p;
    }

    @Test
    void loadsArchiveStartsRealMcpBeforeRegistrationAndShutsDown() throws Exception {
        byte[] archive = archive(reference());
        AtomicInteger registrations = new AtomicInteger(), deletions = new AtomicInteger();
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        int port = control.getAddress().getPort();
        java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> forward =
                new java.util.concurrent.atomic.AtomicReference<>();
        var status = new java.util.concurrent.atomic.AtomicReference<StatusServer>();
        var probesWhileStopping = new java.util.concurrent.atomic.AtomicReference<List<Integer>>();
        control.createContext(
                "/api/v1/service-catalog/download",
                exchange -> PreviewService.respond(
                        exchange,
                        200,
                        Map.of(
                                "data",
                                Map.of(
                                        "name",
                                        "support",
                                        "data",
                                        Base64.getEncoder().encodeToString(archive)))));
        control.createContext("/api/v1/forwards", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                // Wanaku rejects a plain POST for an existing forward: a restarted runtime must replace it.
                if (!"replace=true".equals(exchange.getRequestURI().getQuery())) {
                    PreviewService.respond(exchange, 409, Map.of("error", "forward already exists"));
                    return;
                }
                var body = JSON.readTree(exchange.getRequestBody());
                forward.set(body);
                try {
                    McpReadiness.verify(URI.create(body.path("address").asText()), "route_support");
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                registrations.incrementAndGet();
                PreviewService.respond(
                        exchange,
                        200,
                        Map.of("data", Map.of("forward", Map.of("available", true), "tools_discovered", 1)));
            } else if (exchange.getRequestMethod().equals("GET")) {
                // Shutdown checks forward ownership here, before it deletes the forward.
                try {
                    probesWhileStopping.set(status.get().probes());
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
            } else {
                deletions.incrementAndGet();
                PreviewService.respond(exchange, 200, Map.of("data", Map.of("removed", "reference")));
            }
        });
        control.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        control.start();
        try (var provider = new LocalProvider(0)) {
            int mcp = freePort();
            var configuration = settings(archive, port, port, mcp, provider.port());
            try (var runtime = new SemanticRuntime(RuntimeSettings.from(configuration));
                    var statusServer = StatusServer.start(runtime)) {
                status.set(statusServer);
                runtime.start();
                assertEquals("ready", runtime.status().get("state"));
                assertReadyProbeContract(statusServer);
                assertEquals(CatalogLoader.CAMEL_VERSION, runtime.status().get("camelVersion"));
                assertEquals("20261006.103638", runtime.status().get("catalogCamelBuild"));
                assertFalse(runtime.status().containsKey("camelBuild"));
                assertEquals(1, registrations.get());
                assertEquals(
                        "20261006.103638",
                        forward.get()
                                .path("labels")
                                .path("wsr.catalog-camel-build")
                                .asText());
                assertFalse(forward.get().path("labels").has("wsr.camel-build"));
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
                    assertEquals("route_support", client.listTools().getFirst().name());
                    for (String message :
                            new String[] {"billing", "technical", "unmatched", "failure", "invalid", "action_failure"
                            }) {
                        var request = ToolExecutionRequest.builder()
                                .name("route_support")
                                .arguments(JSON.writeValueAsString(Map.of("message", message)))
                                .build();
                        if (message.equals("failure")
                                || message.equals("invalid")
                                || message.equals("action_failure")) {
                            assertThrows(ToolExecutionException.class, () -> client.executeTool(request));
                        } else {
                            var result = client.executeTool(request);
                            assertFalse(result.isError(), result.resultText());
                            assertTrue(result.resultText()
                                    .contains(
                                            switch (message) {
                                                case "billing" -> "Billing demo response";
                                                case "technical" -> "Technical demo response";
                                                default -> "No suitable support action.";
                                            }));
                        }
                    }
                }
                assertEquals(6, provider.evaluations.get());
                // Shutdown must complete ownership GET/DELETE even when its caller was interrupted.
                var interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
                try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    executor.submit(() -> {
                                Thread.currentThread().interrupt();
                                try {
                                    runtime.close();
                                } finally {
                                    interruptRestored.set(Thread.currentThread().isInterrupted());
                                }
                                return null;
                            })
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
                assertTrue(interruptRestored.get());
                assertEquals("stopped", runtime.status().get("state"));
                assertEquals(List.of(503, 503, 503), probesWhileStopping.get(), "Probes must fail before deletion");
                assertEquals(List.of(503, 503, 503), statusServer.probes());
                assertEquals(200, statusServer.probe("GET", "/api/v1/status"));
            }
            assertEquals(1, deletions.get());
            assertEquals(0, Files.list(directory).count());
        } finally {
            control.stop(0);
        }
    }

    private static void assertReadyProbeContract(StatusServer status) throws Exception {
        assertEquals(List.of(200, 200, 200), status.probes());
        assertEquals(200, status.probe("GET", "/api/v1/status"));
        assertEquals(200, status.probe("POST", "/api/v1/status"), "Status keeps accepting any method");
        assertEquals(405, status.probe("POST", "/api/v1/health/ready"));
        assertEquals(405, status.probe("PUT", "/api/v1/health/live"));
        assertEquals(405, status.probe("DELETE", "/api/v1/health/started"));
        assertEquals(404, status.probe("GET", "/api/v1/health/ready/x"));
        assertEquals(404, status.probe("GET", "/api/v1/health/unknown"));
        assertEquals(404, status.probe("GET", "/api/v1/status/x"));
        String body = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                        + status.server().getAddress().getPort() + "/api/v1/health/ready"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        assertEquals("ready", JSON.readTree(body).path("state").asText());
        assertEquals("reference", JSON.readTree(body).path("forwardName").asText());
    }

    @Test
    void unsafeMismatchMissingAndIncompatibleArchivesNeverRegister() throws Exception {
        byte[] unsafe = archive(Map.of("../escaped", new byte[] {1}));
        RuntimeSettings unsafeSettings = RuntimeSettings.from(settings(unsafe, 1, 1, 1, 1));
        assertThrows(java.io.IOException.class, () -> new CatalogLoader()
                .extract(unsafeSettings.catalog().reference(), unsafeSettings.runtime(), unsafe));
        assertFalse(Files.exists(directory.getParent().resolve("escaped")));
        byte[] valid = archive(reference());
        Properties mismatch = settings(valid, 1, 1, 1, 1);
        mismatch.setProperty("wsr.catalog.sha256", "0".repeat(64));
        RuntimeSettings mismatchSettings = RuntimeSettings.from(mismatch);
        assertThrows(java.io.IOException.class, () -> new CatalogLoader()
                .extract(mismatchSettings.catalog().reference(), mismatchSettings.runtime(), valid));
        var incompatible = reference();
        incompatible.put(
                "support/semantic-router.properties",
                new String(incompatible.get("support/semantic-router.properties"))
                        .replace("4.23.0", "4.22.1")
                        .getBytes());
        byte[] changed = archive(incompatible);
        RuntimeSettings changedSettings = RuntimeSettings.from(settings(changed, 1, 1, 1, 1));
        assertThrows(java.io.IOException.class, () -> new CatalogLoader()
                .extract(changedSettings.catalog().reference(), changedSettings.runtime(), changed));
        var missing = reference();
        missing.remove("support/support.camel.yaml");
        byte[] absent = archive(missing);
        RuntimeSettings absentSettings = RuntimeSettings.from(settings(absent, 1, 1, 1, 1));
        assertThrows(java.io.IOException.class, () -> new CatalogLoader()
                .extract(absentSettings.catalog().reference(), absentSettings.runtime(), absent));
        assertEquals(0, Files.list(directory).count());
    }

    @Test
    void failedStartupExpertSnapshotAndRegistrationLeaveNoUnusableForward() throws Exception {
        for (String scenario : new String[] {"yaml", "expert", "registration", "conflict"}) {
            boolean invalidYaml = scenario.equals("yaml");
            boolean conflict = scenario.equals("conflict");
            boolean beforeRegistration = !scenario.equals("registration") && !conflict;
            var entries = reference();
            if (invalidYaml) {
                entries.put("support/support.camel.yaml", "- route: invalid".getBytes());
            }
            byte[] archive = archive(entries);
            AtomicInteger registrations = new AtomicInteger(), deletions = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> forward =
                    new java.util.concurrent.atomic.AtomicReference<>();
            HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            int port = control.getAddress().getPort();
            control.createContext(
                    "/api/v1/service-catalog/download",
                    exchange -> PreviewService.respond(
                            exchange,
                            200,
                            Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive)))));
            control.createContext("/api/v1/forwards", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    forward.set(JSON.readTree(exchange.getRequestBody()));
                    registrations.incrementAndGet();
                    if (conflict) {
                        PreviewService.respond(exchange, 409, Map.of("error", "forward already exists"));
                        return;
                    }
                    PreviewService.respond(
                            exchange,
                            200,
                            Map.of("data", Map.of("forward", Map.of("available", false), "tools_discovered", 0)));
                } else if (exchange.getRequestMethod().equals("GET")) {
                    PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
                } else {
                    deletions.incrementAndGet();
                    PreviewService.respond(exchange, 200, Map.of("removed", "reference"));
                }
            });
            control.start();
            try (var provider = new LocalProvider(0)) {
                Properties configuration = settings(archive, port, port, freePort(), provider.port());
                if (scenario.equals("expert")) {
                    configuration.setProperty("wsr.semantic-route.expert-bean", "differentPublishedExpert");
                }
                try (var runtime = new SemanticRuntime(RuntimeSettings.from(configuration));
                        var status = StatusServer.start(runtime)) {
                    assertThrows(Exception.class, runtime::start);
                    assertEquals("failed", runtime.status().get("state"));
                    assertEquals(List.of(503, 503, 503), status.probes(), scenario);
                    assertEquals(200, status.probe("GET", "/api/v1/status"));
                    assertEquals(beforeRegistration ? 0 : 1, registrations.get());
                    assertEquals(beforeRegistration ? 0 : 1, deletions.get());
                    if (!beforeRegistration) {
                        // The status names the failed step, so an operator can see it in the pod status.
                        assertEquals(
                                conflict
                                        ? "Wanaku forward registration failed (HTTP 409)"
                                        : "Wanaku did not discover a usable WSR forward (available=false, tools_discovered=0)",
                                runtime.status().get("failure"));
                    }
                }
                assertEquals(0, provider.evaluations.get());
                assertEquals(0, Files.list(directory).count());
            } finally {
                control.stop(0);
            }
        }
    }

    @Test
    void acceptedRegistrationWithMalformedLostOrStalledResponseRollsBackItsOwnForward() throws Exception {
        for (String responseMode : new String[] {"malformed", "lost", "stalled", "replacement"}) {
            byte[] archive = archive(reference());
            AtomicInteger registrations = new AtomicInteger(), deletions = new AtomicInteger();
            var forward = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
            var release = new java.util.concurrent.CountDownLatch(1);
            HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            int port = control.getAddress().getPort();
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            control.setExecutor(executor);
            control.createContext(
                    "/api/v1/service-catalog/download",
                    exchange -> PreviewService.respond(
                            exchange,
                            200,
                            Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive)))));
            control.createContext("/api/v1/forwards", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    var persisted = JSON.readTree(exchange.getRequestBody());
                    forward.set(persisted);
                    registrations.incrementAndGet();
                    if (responseMode.equals("replacement")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted.path("labels"))
                                .put("wsr.instance", "replacement-instance");
                    }
                    if (responseMode.equals("lost")) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write(new byte[] {'{'});
                        exchange.close();
                    } else if (responseMode.equals("stalled")) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(new byte[] {'{'});
                        exchange.getResponseBody().flush();
                        try {
                            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.close();
                    } else {
                        byte[] malformed = "{".getBytes();
                        exchange.sendResponseHeaders(200, malformed.length);
                        try (var body = exchange.getResponseBody()) {
                            body.write(malformed);
                        }
                    }
                } else if (exchange.getRequestMethod().equals("GET")) {
                    PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
                } else {
                    deletions.incrementAndGet();
                    PreviewService.respond(exchange, 200, Map.of("removed", "reference"));
                }
            });
            control.start();
            try (var provider = new LocalProvider(0)) {
                int mcp = freePort();
                Properties deployment = settings(archive, port, port, mcp, provider.port());
                deployment.setProperty("wsr.http.timeout-ms", "1000");
                try (var runtime = new SemanticRuntime(RuntimeSettings.from(deployment));
                        var status = StatusServer.start(runtime)) {
                    assertThrows(Exception.class, runtime::start, responseMode);
                    assertEquals("failed", runtime.status().get("state"));
                    assertEquals(List.of(503, 503, 503), status.probes(), responseMode);
                    assertEquals(1, registrations.get());
                    assertEquals(responseMode.equals("replacement") ? 0 : 1, deletions.get());
                    assertThrows(
                            java.net.ConnectException.class,
                            () -> {
                                try (var socket = new java.net.Socket("127.0.0.1", mcp)) {}
                            },
                            "Failed startup must stop its MCP listener");
                }
                assertEquals(0, Files.list(directory).count());
            } finally {
                release.countDown();
                control.stop(0);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void stalledCatalogFetchTimesOutBeforeStartingOrRegistering() throws Exception {
        byte[] archive = archive(reference());
        AtomicInteger registrations = new AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        int port = control.getAddress().getPort();
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        control.setExecutor(executor);
        control.createContext("/api/v1/service-catalog/download", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[] {'{'});
            exchange.getResponseBody().flush();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        control.createContext("/api/v1/forwards", exchange -> {
            registrations.incrementAndGet();
            PreviewService.respond(exchange, 200, Map.of());
        });
        control.start();
        try {
            var deployment = settings(archive, port, port, freePort(), 1);
            deployment.setProperty("wsr.http.timeout-ms", "250");
            try (var runtime = new SemanticRuntime(RuntimeSettings.from(deployment))) {
                assertThrows(java.net.http.HttpTimeoutException.class, runtime::start);
                assertEquals("failed", runtime.status().get("state"));
                assertEquals(0, registrations.get());
            }
            assertEquals(0, Files.list(directory).count());
        } finally {
            release.countDown();
            control.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void failedFetchIsObservableAndCleansUp() throws Exception {
        byte[] valid = archive(reference());
        try (var runtime = new SemanticRuntime(
                RuntimeSettings.from(settings(valid, freePort(), freePort(), freePort(), freePort())))) {
            assertThrows(Exception.class, runtime::start);
            assertEquals("failed", runtime.status().get("state"));
            assertNotNull(runtime.status().get("failure"));
        }
    }

    @Test
    void fatalStartupFailureWritesTheStatusToTheTerminationLog() throws Exception {
        Path log = Files.createFile(directory.resolve("termination-log"));
        List<String> arguments = DeploymentOptionsTest.runtimeArguments();
        arguments.set(arguments.indexOf("--barn-url") + 1, "http://127.0.0.1:" + freePort());
        arguments.addAll(List.of(
                "--status-port",
                Integer.toString(freePort()),
                "--work-directory",
                Files.createDirectory(directory.resolve("work")).toString()));
        System.setProperty("wsr.termination-log", log.toString());
        try {
            var result = ApplicationTest.execute(arguments.toArray(String[]::new));
            assertEquals(1, result.code(), result.error());
            var message = JSON.readTree(Files.readAllBytes(log));
            assertEquals("failed", message.path("state").asText());
            assertFalse(message.path("failure").asText().isEmpty());
            assertTrue(result.error().contains(Files.readString(log)));
        } finally {
            System.clearProperty("wsr.termination-log");
        }
    }

    @Test
    void terminationLogIsTruncatedAndSkippedWhenThePathIsNotWritable() throws Exception {
        Path log = Files.createFile(directory.resolve("termination-log"));
        Path absent = directory.resolve("absent-termination-log");
        try {
            System.setProperty("wsr.termination-log", log.toString());
            RuntimeCommand.writeTerminationLog("x".repeat(5000).getBytes());
            assertEquals(4096, Files.size(log));
            System.setProperty("wsr.termination-log", absent.toString());
            RuntimeCommand.writeTerminationLog("{}".getBytes());
            assertFalse(Files.exists(absent));
        } finally {
            System.clearProperty("wsr.termination-log");
        }
    }
}
