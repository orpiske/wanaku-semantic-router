package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private HttpServer server;
    private byte[] archive;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws Exception {
        archive = RuntimeTest.archive(RuntimeTest.reference());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/service-catalog/download", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void loadsSdkCatalogFormsWithBearerAuthorization() throws Exception {
        Map<String, Object> store = Map.of(
                "name", "semantic-support-spike", "data", Base64.getEncoder().encodeToString(archive), "future", true);
        for (Object form : List.of(store, List.of(store), Map.of("data", store), Map.of("data", List.of(store)))) {
            response.set(JSON.writeValueAsString(form));
            var catalog = fetch();
            try {
                assertEquals("1", catalog.manifest().getProperty("catalog.revision"));
                assertEquals("Bearer secret-token", authorization.get());
            } finally {
                CatalogLoader.delete(catalog.root());
            }
        }
    }

    @Test
    void remoteErrorsAndMalformedJsonDoNotExposeResponseBodies() throws Exception {
        response.set("secret-token private-configuration");
        for (int code : List.of(403, 200)) {
            status = code;
            var error = assertThrows(IOException.class, this::fetch);
            assertFalse(error.toString().contains("secret-token"));
            assertFalse(error.toString().contains("private-configuration"));
            assertNull(error.getCause(), "SDK response snippets must not escape through nested exceptions");
        }
    }

    private CatalogLoader.Catalog fetch() throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        var reference = new RuntimeSettings.CatalogReference("semantic-support-spike", "support", "1", digest, null);
        var settings = new RuntimeSettings.CatalogSettings(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "secret-token", reference);
        var runtime = new RuntimeSettings.RuntimeOptions(8090, "127.0.0.1", directory, Duration.ofSeconds(2));
        return new CatalogLoader().fetch(settings, runtime);
    }
}
