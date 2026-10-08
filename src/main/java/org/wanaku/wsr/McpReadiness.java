package org.wanaku.wsr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

/** Uses the same maintained MCP client as Wanaku's CLI before registering a forward. */
final class McpReadiness {
    static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private McpReadiness() {}

    static void verify(URI endpoint, String expectedTool) throws Exception {
        verify(endpoint, expectedTool, EXCHANGE_TIMEOUT, CLEANUP_TIMEOUT);
    }

    static void verify(URI endpoint, String expectedTool, Duration exchangeTimeout, Duration cleanupTimeout)
            throws Exception {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(endpoint.toString())
                .customHeaders(Map.of("MCP-Protocol-Version", PROTOCOL_VERSION))
                .timeout(exchangeTimeout)
                .logRequests(false)
                .logResponses(false)
                .build();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var owner = new ClientOwner();
        Future<?> readiness = executor.submit(() -> {
            var client = createClient(transport, exchangeTimeout);
            if (!owner.attach(client)) {
                close(endpoint, transport, client, cleanupTimeout);
                throw new IOException("MCP readiness stopped");
            }
            verifyTools(client, expectedTool);
            return null;
        });
        Exception failure = null;
        try {
            await(readiness, exchangeTimeout.multipliedBy(2), "MCP readiness failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = e;
            throw e;
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            McpClient client = owner.stop();
            readiness.cancel(true);
            Future<?> cleanup = executor.submit(() -> {
                close(endpoint, transport, client, cleanupTimeout);
                return null;
            });
            completeCleanup(cleanup, cleanupTimeout, failure, executor);
        }
    }

    private static McpClient createClient(StreamableHttpMcpTransport transport, Duration timeout) {
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .protocolVersion(PROTOCOL_VERSION)
                .initializationTimeout(timeout)
                .toolExecutionTimeout(timeout)
                .autoHealthCheck(false)
                .build();
    }

    private static void verifyTools(McpClient client, String expectedTool) throws IOException {
        var tools = client.listTools();
        if (tools.size() != 1 || !expectedTool.equals(tools.getFirst().name())) {
            throw new IOException("MCP tool discovery differs from catalog contract");
        }
    }

    private static void completeCleanup(
            Future<?> cleanup, Duration timeout, Exception failure, ExecutorService executor) throws IOException {
        try {
            // SDK close can wait for an HTTP stream. Cleanup has a separate complete deadline.
            awaitCleanup(cleanup, timeout);
        } catch (IOException e) {
            if (failure == null) {
                throw e;
            }
            failure.addSuppressed(e);
        } finally {
            cleanup.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void await(Future<?> operation, Duration timeout, String message)
            throws IOException, InterruptedException {
        try {
            operation.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new IOException(message, e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(message + ": timed out", e);
        }
    }

    private static void awaitCleanup(Future<?> cleanup, Duration timeout) throws IOException {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                try {
                    long remaining = Math.max(1, deadline - System.nanoTime());
                    cleanup.get(remaining, TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException e) {
                    throw new IOException("MCP readiness cleanup failed", e);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void close(URI endpoint, StreamableHttpMcpTransport transport, McpClient client, Duration timeout)
            throws IOException {
        try {
            String session = transport.getMcpSessionId();
            if (session != null) {
                var response = Http.exchange(HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                        .DELETE()
                        .build());
                int status = response.statusCode();
                if ((status < 200 || status >= 300) && status != 404 && status != 405) {
                    throw new IOException("MCP readiness session cleanup failed");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP readiness session cleanup interrupted", e);
        } finally {
            closeClient(transport, client);
        }
    }

    private static void closeClient(StreamableHttpMcpTransport transport, McpClient client) throws IOException {
        if (client == null) {
            transport.close();
        } else {
            try {
                client.close();
            } catch (Exception e) {
                throw new IOException("MCP readiness client cleanup failed", e);
            }
        }
    }

    /** A late constructor cannot publish a client after readiness has begun cleanup. */
    private static final class ClientOwner {
        private McpClient client;
        private boolean closed;

        synchronized boolean attach(McpClient candidate) {
            if (closed) {
                return false;
            }
            client = candidate;
            return true;
        }

        synchronized McpClient stop() {
            closed = true;
            return client;
        }
    }
}
