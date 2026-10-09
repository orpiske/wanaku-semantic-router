package org.wanaku.wsr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.apache.camel.Exchange;
import org.apache.camel.language.semantic.SemanticLanguage;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticAdapter;
import org.apache.camel.semantic.SemanticCapabilities;
import org.apache.camel.semantic.SemanticEvaluation;
import org.apache.camel.semantic.SemanticEvaluations;
import org.apache.camel.semantic.SemanticResult;
import org.apache.camel.support.DefaultExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Isolated semantic evaluation worker. It never accepts executable YAML or loads action routes. */
public final class PreviewService implements AutoCloseable {
    public record Request(String expertBean, String operation, Map<String, Object> parameters, Object state) {
        public Request {
            parameters = parameters == null ? Map.of() : parameters;
        }
    }

    private static final class InvalidRequestException extends IllegalArgumentException {
        private InvalidRequestException() {
            super("Invalid preview contract");
        }
    }

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
                return evaluate(request);
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
        } catch (java.util.concurrent.ExecutionException e) {
            boolean invalid = e.getCause() instanceof InvalidRequestException;
            respond(exchange, invalid ? 400 : 502, Map.of("error", invalid ? "invalid_request" : "evaluation_failed"));
        } catch (Exception e) {
            evaluation.cancel(true);
            respond(exchange, 502, Map.of("error", "evaluation_failed"));
        } finally {
            exchange.close();
        }
    }

    public Map<String, Object> evaluate(Request request) throws Exception {
        validate(request);
        boolean enabled = List.of(
                        RuntimeSettings.required(deployment, "wsr.experts").split(","))
                .stream()
                .map(String::trim)
                .anyMatch(request.expertBean()::equals);
        if (!enabled) {
            throw new InvalidRequestException();
        }
        Main main = new Main();
        try {
            SemanticCapabilities.Operation operation = configureEvaluation(main, request);
            main.start();
            return evaluateNative(main, request, operation);
        } finally {
            main.stop();
        }
    }

    private SemanticCapabilities.Operation configureEvaluation(Main main, Request request) throws Exception {
        main.addProperty("camel.main.name", "wsr-semantic-preview");
        Experts.configure(main, deployment, request.expertBean());
        SemanticAdapter adapter =
                main.getCamelContext().getRegistry().lookupByNameAndType(request.expertBean(), SemanticAdapter.class);
        SemanticCapabilities capabilities = SemanticCapabilities.from(adapter.getClass());
        SemanticEvaluation evaluation;
        SemanticCapabilities.Operation operation;
        try {
            evaluation =
                    new SemanticEvaluation(request.operation(), request.expertBean(), "${body}", request.parameters());
            operation = capabilities.operation(request.operation());
            operation.validate(evaluation.getParameters());
            operation.validateInput(request.state());
            adapter.validate(evaluation);
            adapter.validateInput(evaluation, request.state());
        } catch (IllegalArgumentException e) {
            // Only preflight validation failures are client errors; inference failures remain 502.
            throw new InvalidRequestException();
        }
        SemanticEvaluations.get(main.getCamelContext()).replace("wsr:preview", Map.of("preview", evaluation));
        return operation;
    }

    private static Map<String, Object> evaluateNative(
            Main main, Request request, SemanticCapabilities.Operation operation) {
        SemanticLanguage language = (SemanticLanguage) main.getCamelContext().resolveLanguage("semantic");
        var expression = language.createExpression("ref:preview");
        expression.init(main.getCamelContext());
        Exchange exchange = new DefaultExchange(main.getCamelContext());
        exchange.getMessage().setBody(request.state());
        Object value = expression.evaluate(exchange, Object.class);
        SemanticResult result = exchange.getProperty(SemanticLanguage.RESULT, SemanticResult.class);
        return Map.of(
                "resultType",
                operation.getResultType().name().toLowerCase(Locale.ROOT),
                "value",
                value,
                "diagnostics",
                diagnostics(result));
    }

    private static Map<String, Object> diagnostics(SemanticResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (result != null) {
            if (result.getProbability() != null) {
                diagnostics.put("probability", result.getProbability());
            }
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
        if (request == null
                || request.expertBean() == null
                || request.operation() == null
                || request.operation().isBlank()
                || !(request.state() instanceof String
                        || request.state() instanceof Map<?, ?>
                        || request.state() instanceof List<?>)) {
            throw new InvalidRequestException();
        }
        try {
            RuntimeSettings.identifier(request.expertBean());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException();
        }
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
