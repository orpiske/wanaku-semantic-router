package org.wanaku.wsr;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** Deterministic HTTP fixture for the real native TypeSafe AI adapter, never inference accuracy. */
public final class LocalProvider implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    public final AtomicInteger evaluations = new AtomicInteger();

    public LocalProvider(int port) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.createContext("/v1/systemone", exchange -> {
            evaluations.incrementAndGet();
            var request = JSON.readTree(exchange.getRequestBody());
            String state = request.path("state").asText();
            if (state.equals("failure")) {
                PreviewService.respond(exchange, 503, Map.of("error", "test provider failure"));
                return;
            }
            if (state.equals("timeout")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String label =
                    switch (state) {
                        case "billing", "technical" -> state;
                        case "action_failure" -> "billing";
                        case "invalid" -> "invalid_label";
                        default -> "no_match";
                    };
            Map<String, Object> answers = new LinkedHashMap<>();
            request.path("questions").fields().forEachRemaining(question -> {
                Map<String, Double> probabilities = new LinkedHashMap<>();
                question.getValue()
                        .path("criteria")
                        .fieldNames()
                        .forEachRemaining(key -> probabilities.put(key, key.equals(label) ? 1.0 : 0.0));
                answers.put(
                        question.getKey(),
                        Map.of("type", "choice", "choice", label, "probabilities", probabilities, "confidence", 1.0));
            });
            PreviewService.respond(
                    exchange,
                    200,
                    Map.of(
                            "model",
                            "fixture",
                            "usage",
                            Map.of("input_tokens", 1, "output_tokens", 1),
                            "answers",
                            answers));
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        LocalProvider provider = new LocalProvider(args.length == 0 ? 8093 : Integer.parseInt(args[0]));
        System.out.println("Provider fixture listening on " + provider.port());
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
