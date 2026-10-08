package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises shutdown while catalog download or registration still owns the startup thread. */
class RuntimeLifecycleTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "shutdown during registration = {0}")
    @ValueSource(booleans = {false, true})
    @Timeout(15)
    void shutdownCancelsStalledStartupAndPreventsLaterAcquisitionOrRegistration(boolean registrationStage)
            throws Exception {
        byte[] archive = RuntimeTest.archive(RuntimeTest.reference());
        byte[] response = RuntimeTest.JSON.writeValueAsBytes(
                Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive))));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger(),
                registrations = new AtomicInteger(),
                deletions = new AtomicInteger();
        var forward = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        control.setExecutor(serverExecutor);
        control.createContext("/api/v1/service-catalog/download", exchange -> {
            downloads.incrementAndGet();
            if (registrationStage) {
                PreviewService.respond(
                        exchange,
                        200,
                        Map.of("data", Map.of("data", Base64.getEncoder().encodeToString(archive))));
            } else {
                stalled(exchange, response, entered, release);
            }
        });
        control.createContext("/api/v1/forwards", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                registrations.incrementAndGet();
                forward.set(RuntimeTest.JSON.readTree(exchange.getRequestBody()));
                byte[] registered = RuntimeTest.JSON.writeValueAsBytes(
                        Map.of("data", Map.of("forward", Map.of("available", true), "tools_discovered", 1)));
                stalled(exchange, registered, entered, release);
            } else if (exchange.getRequestMethod().equals("GET")) {
                PreviewService.respond(exchange, 200, Map.of("data", forward.get()));
            } else {
                deletions.incrementAndGet();
                PreviewService.respond(exchange, 200, Map.of("removed", "reference"));
            }
        });
        control.start();
        RuntimeTest fixture = new RuntimeTest();
        fixture.directory = directory;
        int port = control.getAddress().getPort(), mcp = RuntimeTest.freePort();
        var settings = fixture.settings(archive, port, port, mcp, 1);
        // The explicit stop must cancel startup before this longer HTTP timeout can expire.
        settings.setProperty("wsr.http.timeout-ms", "30000");
        try (var runtime = new SemanticRuntime(RuntimeSettings.from(settings));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var starting = executor.submit(() -> {
                runtime.start();
                return null;
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Startup must be awaiting the controlled HTTP response");
                var closing = executor.submit(() -> {
                    runtime.close();
                    return null;
                });
                closing.get(3, TimeUnit.SECONDS);
                assertEquals("stopped", runtime.status().get("state"));
                release.countDown();
                var failed = assertThrows(ExecutionException.class, () -> starting.get(3, TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, failed.getCause());
                assertEquals(
                        0, failed.getCause().getSuppressed().length, "Cancellation must not cause cleanup to fail");
                assertEquals(registrationStage ? 1 : 0, registrations.get());
                assertEquals(
                        registrationStage ? 1 : 0,
                        deletions.get(),
                        "A remotely accepted registration must be removed during cancellation");
                assertEquals(1, downloads.get());
                try (var files = Files.list(directory)) {
                    assertEquals(0, files.count(), "A stopped startup must leave no extraction directory");
                }
                try (var listener = new ServerSocket()) {
                    listener.bind(new InetSocketAddress("127.0.0.1", mcp));
                }
                assertThrows(IllegalStateException.class, runtime::start);
                assertEquals(1, downloads.get(), "Starting a stopped runtime must not issue another catalog request");
            } finally {
                release.countDown();
                starting.cancel(true);
            }
        } finally {
            release.countDown();
            control.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static void stalled(HttpExchange exchange, byte[] response, CountDownLatch entered, CountDownLatch release)
            throws java.io.IOException {
        exchange.sendResponseHeaders(200, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response, 0, 1);
            body.flush();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
                body.write(response, 1, response.length - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // The cancelled HTTP client can close the response before the fixture releases it.
            }
        }
    }

    @Test
    @Timeout(15)
    void shutdownDoesNotStarveOwnershipCleanupWithTwoVirtualThreadCarriers() throws Exception {
        Path work = Files.createDirectory(directory.resolve("runtime"));
        Path output = directory.resolve("two-carriers.log");
        // The scheduler reads these settings once, so this regression needs an isolated JVM.
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djdk.virtualThreadScheduler.parallelism=2",
                        "-Djdk.virtualThreadScheduler.maxPoolSize=2",
                        "-Djdk.tracePinnedThreads=full",
                        "-cp",
                        System.getProperty("java.class.path"),
                        RuntimeLifecycleTest.class.getName(),
                        work.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "The isolated lifecycle regression must complete");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        RuntimeLifecycleTest fixture = new RuntimeLifecycleTest();
        fixture.directory = Path.of(args[0]);
        fixture.shutdownCancelsStalledStartupAndPreventsLaterAcquisitionOrRegistration(true);
    }

    @Test
    void closedRuntimeRejectsStartupBeforeOpeningTheCatalogConnection() throws Exception {
        byte[] archive = RuntimeTest.archive(RuntimeTest.reference());
        RuntimeTest fixture = new RuntimeTest();
        fixture.directory = directory;
        try (var runtime = new SemanticRuntime(RuntimeSettings.from(fixture.settings(archive, 1, 1, 8090, 1)))) {
            runtime.close();
            assertThrows(IllegalStateException.class, runtime::start);
            assertEquals("stopped", runtime.status().get("state"));
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        }
    }
}
