package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpTest {
    @Test
    void boundsPartialResponseCompletionAndCancelsTheTransfer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        CountDownLatch headers = new CountDownLatch(2), closed = new CountDownLatch(2);
        server.createContext("/partial", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[] {1});
                output.flush();
                headers.countDown();
                // The client must close this unfinished transfer, not merely stop waiting for it.
                for (int i = 0; i < 100; i++) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    output.write(new byte[8192]);
                    output.flush();
                }
            } catch (java.io.IOException expected) {
                closed.countDown();
            }
        });
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        try {
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/partial"))
                    .timeout(Duration.ofMillis(250))
                    .GET()
                    .build();
            assertThrows(HttpTimeoutException.class, () -> Http.exchange(request));
            assertThrows(HttpTimeoutException.class, () -> Http.sdkClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()));
            assertTrue(headers.await(1, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS), "Timed-out body transfer must be cancelled");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(15)
    void cancelledSdkResponseDoesNotBlockCleanupAtSameAuthority(boolean interrupted) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) {
                output.write(1);
                output.flush();
                entered.countDown();
                // Keep the response unfinished while the cancelled client starts an ownership cleanup request.
                release.await(10, TimeUnit.SECONDS);
                output.write(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException expected) {
                // The cancelled exchange closes its response connection.
            }
        });
        server.createContext("/cleanup", exchange -> {
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var cleanup = HttpRequest.newBuilder(base.resolve("/cleanup"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            // Initialize the shared transport before measuring the stalled request's existing body deadline.
            assertEquals(
                    "ok",
                    Http.sdkClient()
                            .send(cleanup, HttpResponse.BodyHandlers.ofString())
                            .body());
            var request = HttpRequest.newBuilder(base.resolve("/stalled"))
                    .timeout(interrupted ? Duration.ofSeconds(30) : Duration.ofMillis(250))
                    .GET()
                    .build();
            AtomicReference<Thread> caller = new AtomicReference<>();
            var transfer = executor.submit(() -> {
                caller.set(Thread.currentThread());
                return Http.sdkClient().send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS), "The response must stall after headers and its first byte");
            if (interrupted) {
                caller.get().interrupt();
            }
            var failure = assertThrows(ExecutionException.class, () -> transfer.get(3, TimeUnit.SECONDS));
            Class<? extends Exception> expected = interrupted ? InterruptedException.class : HttpTimeoutException.class;
            assertInstanceOf(expected, failure.getCause());
            // Both requests use the same HttpClient and authority, with the old response still held by the server.
            var response = Http.sdkClient().send(cleanup, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
            assertEquals(1L, release.getCount(), "Cleanup must not depend on the stalled response completing");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void capsBodyBytesBeforeRetainingOversizedData() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[1024]);
            }
        });
        server.start();
        try {
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/large"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var error = assertThrows(java.io.IOException.class, () -> Http.exchange(request, 64));
            assertTrue(error.getMessage().contains("size limit"));
            var sdkError = assertThrows(java.io.IOException.class, () -> Http.sdkClient(64)
                    .send(request, HttpResponse.BodyHandlers.ofString()));
            assertTrue(sdkError.getMessage().contains("size limit"));
        } finally {
            server.stop(0);
        }
    }
}
