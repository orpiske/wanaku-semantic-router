package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Checks no-file preview configuration through the packaged application's public HTTP API. */
class PackagedPreviewIT {
    @TempDir
    Path directory;

    @Test
    @Timeout(35)
    void packagedPreviewClassifiesWithTwoExpertsAndProtectsTheEndpointWithoutAPropertiesFile() throws Exception {
        Process process = null;
        try (var provider = new LocalProvider(0)) {
            int port = RuntimeTest.freePort();
            var arguments = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-jar",
                    System.getProperty("packagedJar"),
                    "preview",
                    "--bind",
                    "127.0.0.1",
                    "--port",
                    Integer.toString(port),
                    "--preview-timeout-ms",
                    "10000",
                    "--max-concurrent",
                    "2",
                    "--preview-token-env",
                    "WSR_TEST_PREVIEW_TOKEN",
                    "--expert",
                    "typesafe=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter",
                    "--expert",
                    "secondary=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter"));
            var settings = PreviewServiceTest.providerSettings(provider.port());
            for (String key : settings.stringPropertyNames().stream()
                    .filter(key -> key.startsWith("camel."))
                    .sorted()
                    .toList()) {
                arguments.addAll(List.of("-p", key + "=" + settings.getProperty(key)));
            }
            Path log = directory.resolve("preview.log");
            var builder =
                    new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("WSR_TEST_PREVIEW_TOKEN", "preview-fixture-token");
            process = builder.start();
            var http = HttpClient.newHttpClient();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            HttpResponse<String> unauthenticated = null;
            while (System.nanoTime() < end && process.isAlive()) {
                try {
                    unauthenticated = http.send(
                            request(port, PreviewServiceTest.request("billing"), null),
                            HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (java.io.IOException ignored) {
                    Thread.sleep(50);
                }
            }
            assertTrue(unauthenticated != null, Files.readString(log));
            assertEquals(401, unauthenticated.statusCode());
            assertEquals(0, provider.evaluations.get(), "Unauthenticated requests must not invoke the provider");
            var rejectedToken = http.send(
                    request(port, PreviewServiceTest.request("billing"), "incorrect-fixture-token"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, rejectedToken.statusCode());
            assertEquals(0, provider.evaluations.get(), "An incorrect token must not invoke the provider");
            for (String expert : List.of("typesafe", "secondary")) {
                var original = PreviewServiceTest.request(expert.equals("typesafe") ? "billing" : "technical");
                var selected = new PreviewService.Request(
                        expert, original.operation(), original.parameters(), original.state());
                var response = http.send(
                        request(port, selected, "preview-fixture-token"), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body() + "\n" + Files.readString(log));
                assertEquals(
                        selected.state(),
                        RuntimeTest.JSON.readTree(response.body()).path("value").asText());
            }
            assertEquals(2, provider.evaluations.get());
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), Files.readString(log));
            assertFalse(Files.exists(directory.resolve("preview.properties")));
            assertFalse(Files.readString(log).contains("preview-fixture-token"));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static HttpRequest request(int port, PreviewService.Request body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/preview"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(RuntimeTest.JSON.writeValueAsBytes(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }
}
