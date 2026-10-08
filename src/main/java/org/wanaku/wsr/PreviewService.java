package org.wanaku.wsr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.apache.camel.Exchange;
import org.apache.camel.language.semantic.SemanticLanguage;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticQuestion;
import org.apache.camel.semantic.SemanticQuestions;
import org.apache.camel.semantic.SemanticResult;
import org.apache.camel.support.DefaultExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Classification-only worker. It never accepts executable YAML or loads action routes. */
public final class PreviewService implements AutoCloseable {
    public record Request(
            String input, String instructions, Map<String, String> criteria, String expertBean, String message) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Properties deployment;
    private final Semaphore slots;
    private final String token;
    private HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PreviewService(Properties deployment) {
        this.deployment = deployment;
        int max = Integer.parseInt(deployment.getProperty("wsr.preview.max-concurrent", "4"));
        if (max < 1 || max > 128) {
            throw new IllegalArgumentException("Invalid preview concurrency limit");
        }
        slots = new Semaphore(max);
        long timeout = Long.parseLong(deployment.getProperty("wsr.preview.timeout-ms", "10000"));
        if (timeout < 1 || timeout > 120000) {
            throw new IllegalArgumentException("Invalid preview timeout");
        }
        token = RuntimeSettings.secret(deployment, "wsr.preview.token-env");
    }

    public void start() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress(
                        deployment.getProperty("wsr.preview.bind", "127.0.0.1"),
                        Integer.parseInt(deployment.getProperty("wsr.preview.port", "8092"))),
                32);
        server.setExecutor(executor);
        server.createContext("/api/v1/preview", this::handlePreview);
        server.start();
    }

    private void handlePreview(HttpExchange exchange) throws IOException {
        if (!acceptRequest(exchange)) {
            return;
        }
        Request request = decodeRequest(exchange);
        if (request != null) {
            evaluatePreview(exchange, request);
        }
    }

    private boolean acceptRequest(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/api/v1/preview")) {
            respond(exchange, 404, Map.of("error", "Not found"));
            return false;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, Map.of("error", "Use POST"));
            return false;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (token != null
                && (auth == null
                        || !MessageDigest.isEqual(
                                auth.getBytes(StandardCharsets.UTF_8),
                                ("Bearer " + token).getBytes(StandardCharsets.UTF_8)))) {
            respond(exchange, 401, Map.of("error", "Authentication required"));
            return false;
        }
        return true;
    }

    private Request decodeRequest(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            if (body.length > 65536) {
                respond(exchange, 413, Map.of("error", "request_too_large"));
                return null;
            }
            Request request = JSON.readValue(body, Request.class);
            validate(request);
            return request;
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            respond(exchange, 400, Map.of("error", "invalid_request"));
            return null;
        }
    }

    private void evaluatePreview(HttpExchange exchange, Request request) throws IOException {
        if (!slots.tryAcquire()) {
            respond(exchange, 429, Map.of("error", "preview_capacity_reached"));
            return;
        }
        var evaluation = executor.submit(() -> {
            try {
                return classify(request);
            } finally {
                slots.release();
            }
        });
        try {
            long timeout = Long.parseLong(deployment.getProperty("wsr.preview.timeout-ms", "10000"));
            respond(exchange, 200, evaluation.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            evaluation.cancel(true);
            respond(exchange, 504, Map.of("error", "evaluation_timeout"));
        } catch (Exception e) {
            evaluation.cancel(true);
            respond(exchange, 502, Map.of("error", "evaluation_failed"));
        } finally {
            exchange.close();
        }
    }

    public Map<String, Object> classify(Request request) throws Exception {
        validate(request);
        Main main = new Main();
        try {
            configureClassification(main, request);
            main.start();
            return evaluateClassification(main, request);
        } finally {
            main.stop();
        }
    }

    private void configureClassification(Main main, Request request) throws Exception {
        main.addProperty("camel.main.name", "wsr-classification-preview");
        Experts.configure(main, deployment, request.expertBean());
        // Use the same native question contract as the generated YAML. No dispatch route exists.
        SemanticQuestion question = new SemanticQuestion(
                SemanticQuestion.Type.CHOICE,
                request.instructions(),
                "${body}",
                request.criteria(),
                null,
                0.5,
                0,
                SemanticQuestion.UncertaintyPolicy.FAIL,
                request.expertBean());
        SemanticQuestions.get(main.getCamelContext()).replace("wsr:preview", Map.of("department", question));
    }

    private static Map<String, Object> evaluateClassification(Main main, Request request) {
        SemanticLanguage language = (SemanticLanguage) main.getCamelContext().resolveLanguage("semantic");
        var expression = language.createExpression("ref:department");
        expression.init(main.getCamelContext());
        Exchange exchange = new DefaultExchange(main.getCamelContext());
        exchange.getMessage().setBody(request.message());
        String label = expression.evaluate(exchange, String.class);
        SemanticResult result = exchange.getProperty(SemanticLanguage.RESULT, SemanticResult.class);
        return Map.of("label", label, "diagnostics", diagnostics(result));
    }

    private static Map<String, Object> diagnostics(SemanticResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (result != null) {
            if (result.getConfidence() != null) {
                diagnostics.put("confidence", result.getConfidence());
            }
            if (!result.getProbabilities().isEmpty()) {
                diagnostics.put("probabilities", result.getProbabilities());
            }
        }
        return diagnostics;
    }

    private static void validate(Request request) {
        validateMessage(request);
        validateCriteriaShape(request.criteria());
        RuntimeSettings.identifier(request.expertBean());
        validateCriteriaEntries(request.criteria());
    }

    private static void validateMessage(Request request) {
        if (!"message".equals(request.input())
                || request.instructions() == null
                || request.instructions().isBlank()
                || request.instructions().length() > 8192
                || request.message() == null
                || request.message().isBlank()
                || request.message().length() > 32768) {
            throw new IllegalArgumentException("Invalid preview contract");
        }
    }

    private static void validateCriteriaShape(Map<String, String> criteria) {
        if (criteria == null || criteria.size() < 2 || criteria.size() > 32 || !criteria.containsKey("no_match")) {
            throw new IllegalArgumentException("Invalid preview contract");
        }
    }

    private static void validateCriteriaEntries(Map<String, String> criteria) {
        criteria.forEach((label, criterion) -> {
            RuntimeSettings.identifier(label);
            if (criterion == null || criterion.isBlank() || criterion.length() > 8192) {
                throw new IllegalArgumentException("Invalid criterion");
            }
        });
    }

    static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
        }
        executor.shutdownNow();
    }
}
