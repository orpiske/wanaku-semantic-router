package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Resolves a publication through the CLI deployment path without starting Camel listeners. */
@Timeout(10)
class SemanticRouteTest {
    static final String ROUTE = "Support router";
    static final String DIGEST = "a".repeat(64);
    static final String ADAPTER = "org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter";

    static ObjectNode publication() {
        var data = RuntimeTest.JSON.createObjectNode();
        data.put("name", ROUTE);
        data.put("toolName", "route_support");
        data.put("catalogName", "published-support-r7");
        data.put("service", "support");
        data.put("revision", "7");
        data.put("sha256", DIGEST);
        data.put("mainFile", "support/support.camel.yaml");
        data.put("camelVersion", CatalogLoader.CAMEL_VERSION);
        data.put("camelBuild", "fixture-build");
        data.put("downloadUrl", "/api/v1/service-catalog/download?name=published-support-r7");
        var expert = data.putObject("expert");
        expert.put("id", "published-expert");
        expert.put("name", "Published expert");
        expert.put("bean", "publishedExpert");
        expert.put("dependency", "org.apache.camel:camel-typesafe-ai:" + CatalogLoader.CAMEL_VERSION);
        expert.put("supportsConfidence", true);
        return data;
    }

    static Properties deployment(Map<String, String> environment, String... args) throws Exception {
        var cli = Application.commandLine();
        List<String> arguments = new ArrayList<>(List.of("runtime"));
        arguments.addAll(List.of(args));
        cli.parseArgs(arguments.toArray(String[]::new));
        return ((RuntimeCommand) cli.getSubcommands().get("runtime").getCommand()).deployment(environment);
    }

    @Test
    void resolvesOnePublishedSelectionAndUsesPublishedExpertWithLocalDefaults() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        var data = publication();
        data.put("draftToolName", "draft_tool");
        data.put("draftExpertBean", "draftExpert");
        try (var barn = new Barn(exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            PreviewService.respond(exchange, 200, Map.of("data", data));
        })) {
            var settings =
                    deployment(Map.of(), "--semantic-route", ROUTE, "--barn-url", barn.url(), "--mcp-port", "8123");
            assertEquals(1, barn.requests.get());
            assertEquals("name=" + ROUTE, query.get());
            assertEquals("published-support-r7", settings.getProperty("wsr.catalog.name"));
            assertEquals("support", settings.getProperty("wsr.catalog.service"));
            assertEquals("7", settings.getProperty("wsr.catalog.revision"));
            assertEquals(DIGEST, settings.getProperty("wsr.catalog.sha256"));
            assertEquals("support/support.camel.yaml", settings.getProperty("wsr.catalog.main"));
            assertEquals("publishedExpert", settings.getProperty("wsr.experts"));
            assertEquals(ADAPTER, settings.getProperty("wsr.expert.publishedExpert.class"));
            assertEquals("route_support", settings.getProperty("wsr.forward.name"));
            assertEquals("http://127.0.0.1:8123/mcp", settings.getProperty("wsr.mcp.address"));
            assertEquals("http://localhost:8080", settings.getProperty("wsr.wanaku.url"));
            assertEquals("{{env:TYPESAFE_API_KEY}}", settings.getProperty("camel.component.typesafe-ai.api-key"));
            assertFalse(settings.containsKey("camel.component.typesafe-ai.model"));
            assertFalse(settings.containsKey("wsr.expert.draftExpert.class"));
        }
    }

    @Test
    void explicitRevisionSelectsThatPublicationAndMatchingPinsAreRetained() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        try (var barn = new Barn(exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            PreviewService.respond(exchange, 200, Map.of("data", publication()));
        })) {
            var settings = deployment(
                    Map.of("TYPESAFE_MODEL", "fixture-model"),
                    "--semantic-route",
                    ROUTE,
                    "--barn-url",
                    barn.url(),
                    "--catalog-revision",
                    "7",
                    "--catalog-sha256",
                    DIGEST,
                    "--service-catalog",
                    "published-support-r7",
                    "--catalog-main",
                    "support/support.camel.yaml",
                    "--name",
                    "custom-forward",
                    "--mcp-address",
                    "http://wsr.example:9000/mcp");
            assertEquals("name=" + ROUTE + "&revision=7", query.get());
            assertEquals("custom-forward", settings.getProperty("wsr.forward.name"));
            assertEquals("http://wsr.example:9000/mcp", settings.getProperty("wsr.mcp.address"));
            assertEquals("{{env:TYPESAFE_MODEL}}", settings.getProperty("camel.component.typesafe-ai.model"));
            assertEquals(1, barn.requests.get());
        }
    }

    @Test
    void explicitCatalogConstraintsCannotChangeTheResolvedPublication() throws Exception {
        try (var barn = new Barn(exchange -> PreviewService.respond(exchange, 200, Map.of("data", publication())))) {
            for (String[] constraint : new String[][] {
                {"--catalog-revision", "8"}, {"--catalog-sha256", "b".repeat(64)},
                {"--service-catalog", "other-catalog"}, {"--service-catalog-system", "other-service"},
                {"--catalog-main", "other/main.camel.yaml"}
            }) {
                assertThrows(
                        Exception.class,
                        () -> deployment(
                                Map.of(),
                                "--semantic-route",
                                ROUTE,
                                "--barn-url",
                                barn.url(),
                                constraint[0],
                                constraint[1]),
                        constraint[0]);
            }
        }
    }

    @Test
    void unknownExpertsRequireAnExplicitClassForThePublishedBean() throws Exception {
        var data = publication();
        ((ObjectNode) data.get("expert")).put("dependency", "example:custom-expert:1.0");
        try (var barn = new Barn(exchange -> PreviewService.respond(exchange, 200, Map.of("data", data)))) {
            assertThrows(
                    Exception.class, () -> deployment(Map.of(), "--semantic-route", ROUTE, "--barn-url", barn.url()));
            var configured = deployment(
                    Map.of(),
                    "--semantic-route",
                    ROUTE,
                    "--barn-url",
                    barn.url(),
                    "--expert",
                    "publishedExpert=example.CustomAdapter");
            assertEquals("publishedExpert", configured.getProperty("wsr.experts"));
            assertEquals("example.CustomAdapter", configured.getProperty("wsr.expert.publishedExpert.class"));
        }
    }

    @Test
    void malformedOrMismatchedPublicationResponsesCannotStartARuntime() throws Exception {
        var responses = new ArrayList<String>();
        for (String field : List.of("name", "catalogName", "service", "revision", "sha256", "mainFile", "expert")) {
            var data = publication();
            data.remove(field);
            responses.add(RuntimeTest.JSON.writeValueAsString(Map.of("data", data)));
        }
        var mismatched = publication();
        mismatched.put("name", "Different router");
        responses.add(RuntimeTest.JSON.writeValueAsString(Map.of("data", mismatched)));
        responses.add("{\"data\":[]}");
        responses.add("{\"data\":");
        responses.add(RuntimeTest.JSON
                .writeValueAsString(Map.of("data", publication()))
                .replace("\"revision\":\"7\"", "\"revision\":\"7\",\"revision\":\"8\""));
        for (String response : responses) {
            try (var barn = new Barn(exchange -> {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
            })) {
                assertThrows(
                        Exception.class,
                        () -> deployment(Map.of(), "--semantic-route", ROUTE, "--barn-url", barn.url()),
                        response);
            }
        }
    }

    @Test
    void resolutionDeadlineIncludesAnUnfinishedResponseBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var barn = new Barn(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("{\"data\":".getBytes(StandardCharsets.UTF_8));
                body.flush();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            long start = System.nanoTime();
            try {
                assertThrows(
                        Exception.class,
                        () -> deployment(
                                Map.of(),
                                "--semantic-route",
                                ROUTE,
                                "--barn-url",
                                barn.url(),
                                "--http-timeout-ms",
                                "100"));
                assertTrue(
                        Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                        "Resolution must stop before the fixture releases its response");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void resolutionErrorsDoNotExposeResponseBodiesOrCredentialValues() throws Exception {
        for (int status : new int[] {401, 404, 409, 500}) {
            try (var barn = new Barn(
                    exchange -> PreviewService.respond(exchange, status, Map.of("error", "secret-fixture-value")))) {
                var result = ApplicationTest.execute("runtime", "--semantic-route", ROUTE, "--barn-url", barn.url());
                assertNotEquals(0, result.code());
                assertFalse(result.error().contains("secret-fixture-value"), result.error());
                assertFalse(result.output().contains("secret-fixture-value"), result.output());
                assertEquals(1, barn.requests.get());
            }
        }
    }

    @Test
    void loopbackAliasesAndIpv6AdvertiseTheirConfiguredListenerPort() throws Exception {
        try (var barn = new Barn(exchange -> PreviewService.respond(exchange, 200, Map.of("data", publication())))) {
            for (String bind : List.of("127.0.0.2", "localhost", "::1")) {
                var settings = deployment(
                        Map.of(),
                        "--semantic-route",
                        ROUTE,
                        "--barn-url",
                        barn.url(),
                        "--bind",
                        bind,
                        "--mcp-port",
                        "8124");
                var uri = java.net.URI.create(settings.getProperty("wsr.mcp.address"));
                assertEquals("http", uri.getScheme());
                assertEquals(8124, uri.getPort());
                assertEquals("/mcp", uri.getPath());
                assertEquals(bind, uri.getHost().replace("[", "").replace("]", ""));
            }
        }
    }

    @Test
    void resolutionRejectsOversizedMetadataResponses() throws Exception {
        var data = publication();
        data.put("padding", "x".repeat(1024 * 1024));
        byte[] oversized = RuntimeTest.JSON.writeValueAsBytes(Map.of("data", data));
        try (var barn = new Barn(exchange -> {
            exchange.sendResponseHeaders(200, oversized.length);
            try (var body = exchange.getResponseBody()) {
                body.write(oversized);
            }
        })) {
            assertThrows(
                    Exception.class, () -> deployment(Map.of(), "--semantic-route", ROUTE, "--barn-url", barn.url()));
        }
    }

    @Test
    void nonlocalBindingRequiresAnExplicitAdvertisedAddress() throws Exception {
        try (var barn = new Barn(exchange -> PreviewService.respond(exchange, 200, Map.of("data", publication())))) {
            assertThrows(
                    Exception.class,
                    () -> deployment(
                            Map.of(), "--semantic-route", ROUTE, "--barn-url", barn.url(), "--bind", "0.0.0.0"));
            var configured = deployment(
                    Map.of(),
                    "--semantic-route",
                    ROUTE,
                    "--barn-url",
                    barn.url(),
                    "--bind",
                    "0.0.0.0",
                    "--mcp-address",
                    "http://wsr.example:8090/mcp");
            assertEquals("http://wsr.example:8090/mcp", configured.getProperty("wsr.mcp.address"));
        }
    }

    private static final class Barn implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        Barn(HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            server.setExecutor(executor);
            server.createContext("/api/v1/semantic-routers/resolve", exchange -> {
                requests.incrementAndGet();
                handler.handle(exchange);
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
