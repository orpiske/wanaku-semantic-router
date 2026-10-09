package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.semantic.SemanticAdapter;
import org.apache.camel.semantic.SemanticEvaluation;
import org.apache.camel.semantic.SemanticExpert;
import org.apache.camel.semantic.SemanticOperation;
import org.apache.camel.semantic.SemanticResult;

import org.junit.jupiter.api.Test;

class PreviewHttpTest {
    @SemanticExpert(
            name = "blocking",
            description = "Cancellation fixture",
            provider = "fixture",
            artifactId = "fixture",
            operations =
                    @SemanticOperation(
                            name = "choice",
                            description = "Blocking choice",
                            inputTypes = SemanticExpert.InputType.TEXT,
                            inputRequirements = "Text",
                            resultType = SemanticExpert.ResultType.CHOICE,
                            resultMeaning = "Support action",
                            parameters = {
                                @org.apache.camel.semantic.SemanticParameter(
                                        name = "instructions",
                                        description = "Instructions",
                                        omission = "Use fixture instructions"),
                                @org.apache.camel.semantic.SemanticParameter(
                                        name = "criteria",
                                        description = "Criteria",
                                        type = java.util.Map.class,
                                        omission = "Use fixture labels")
                            }))
    public static final class BlockingAdapter implements SemanticAdapter {
        static final AtomicInteger running = new AtomicInteger();
        static volatile CountDownLatch entered;
        static volatile CountDownLatch cancelled;
        static volatile CountDownLatch release;
        static volatile CountDownLatch exited;

        static void reset() {
            running.set(0);
            entered = new CountDownLatch(1);
            cancelled = new CountDownLatch(1);
            release = new CountDownLatch(1);
            exited = new CountDownLatch(1);
        }

        @Override
        public void validate(SemanticEvaluation evaluation) {}

        @Override
        public SemanticResult evaluate(SemanticEvaluation evaluation, Object state) throws Exception {
            running.incrementAndGet();
            entered.countDown();
            try {
                // Keep the evaluation alive after cancellation until the test explicitly permits exit.
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        cancelled.countDown();
                    }
                }
                return new SemanticResult("billing", null, java.util.Map.of(), null, java.util.Map.of());
            } finally {
                running.decrementAndGet();
                exited.countDown();
            }
        }
    }

    @Test
    void reportsProviderFailuresAs502AndAppliesTimeoutCapacityUntilExit() throws Exception {
        int port = RuntimeTest.freePort();
        var settings = PreviewServiceTest.providerSettings(1);
        settings.setProperty("wsr.preview.port", Integer.toString(port));
        // Allow cold Camel startup; fixture latches, rather than a sleep window, control evaluation.
        settings.setProperty("wsr.preview.timeout-ms", "5000");
        settings.setProperty("wsr.preview.max-concurrent", "1");
        settings.setProperty("wsr.experts", "typesafe");
        settings.setProperty("wsr.expert.typesafe.class", BlockingAdapter.class.getName());
        BlockingAdapter.reset();
        try (var preview = new PreviewService(settings)) {
            preview.start();
            var response = HttpClient.newHttpClient()
                    .sendAsync(
                            request(port, PreviewServiceTest.request("billing")), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(
                        BlockingAdapter.entered.await(3, TimeUnit.SECONDS),
                        "Provider must enter before testing cancellation");
                var timedOut = response.get(10, TimeUnit.SECONDS);
                assertEquals(504, timedOut.statusCode());
                assertEquals(
                        "evaluation_timeout",
                        RuntimeTest.JSON.readTree(timedOut.body()).path("error").asText());
                assertTrue(
                        BlockingAdapter.cancelled.await(1, TimeUnit.SECONDS),
                        "Deadline must interrupt the active evaluation");
                assertEquals(1, BlockingAdapter.running.get());
                assertEquals(
                        429, send(port, PreviewServiceTest.request("technical")).statusCode());
                assertEquals(1, BlockingAdapter.running.get(), "Rejected request must not start another evaluation");
            } finally {
                BlockingAdapter.release.countDown();
                response.cancel(true);
            }
            assertTrue(BlockingAdapter.exited.await(3, TimeUnit.SECONDS), "Released evaluation must exit");
            assertEquals(0, BlockingAdapter.running.get());
        } finally {
            BlockingAdapter.release.countDown();
        }
        try (var provider = new LocalProvider(0)) {
            var nativeSettings = PreviewServiceTest.providerSettings(provider.port());
            nativeSettings.setProperty("wsr.preview.port", Integer.toString(port));
            try (var preview = new PreviewService(nativeSettings)) {
                preview.start();
                for (String input : new String[] {"failure", "invalid"}) {
                    var response = send(port, PreviewServiceTest.request(input));
                    assertEquals(502, response.statusCode());
                    assertEquals(
                            "evaluation_failed",
                            RuntimeTest.JSON
                                    .readTree(response.body())
                                    .path("error")
                                    .asText());
                }
                assertEquals(
                        400,
                        send(
                                        port,
                                        new PreviewService.Request(
                                                "typesafe",
                                                "choice",
                                                PreviewServiceTest.request("billing")
                                                        .parameters(),
                                                123))
                                .statusCode());
            }
        }
    }

    @Test
    void rejectsWrongHttpRoutesMethodsMalformedContractsAndOversizedBodiesBeforeInference() throws Exception {
        int port = RuntimeTest.freePort();
        try (var provider = new LocalProvider(0)) {
            var settings = PreviewServiceTest.providerSettings(provider.port());
            settings.setProperty("wsr.preview.port", Integer.toString(port));
            try (var preview = new PreviewService(settings)) {
                preview.start();
                assertEquals(
                        404,
                        send(request(port, "/api/v1/preview/child", "POST", "{}"))
                                .statusCode());
                assertEquals(
                        405, send(request(port, "/api/v1/preview", "GET", "")).statusCode());
                String valid = RuntimeTest.JSON.writeValueAsString(PreviewServiceTest.request("billing"));
                String unknownField = valid.substring(0, valid.length() - 1) + ",\"actionEndpoint\":\"exec:bad\"}";
                for (String invalid : new String[] {
                    "{",
                    "{}",
                    unknownField,
                    "{\"input\":\"message\",\"instructions\":\"Choose\",\"criteria\":{},\"expertBean\":\"typesafe\",\"message\":\"billing\"}",
                    " ".repeat(65536)
                }) {
                    var rejected = send(request(port, "/api/v1/preview", "POST", invalid));
                    assertEquals(400, rejected.statusCode());
                    assertEquals(
                            "invalid_request",
                            RuntimeTest.JSON
                                    .readTree(rejected.body())
                                    .path("error")
                                    .asText());
                }
                var oversized = send(request(port, "/api/v1/preview", "POST", " ".repeat(65537)));
                assertEquals(413, oversized.statusCode());
                assertEquals(
                        "request_too_large",
                        RuntimeTest.JSON
                                .readTree(oversized.body())
                                .path("error")
                                .asText());
                assertEquals(0, provider.evaluations.get());
                assertEquals(
                        200, send(port, PreviewServiceTest.request("billing")).statusCode());
                assertEquals(1, provider.evaluations.get());
            }
        }
    }

    @Test
    void validatesContractsBeforeInferenceAndRejectsMalformedTypedResults() throws Exception {
        int port = RuntimeTest.freePort();
        var settings = PreviewServiceTest.fixtureSettings();
        settings.setProperty("wsr.preview.port", Integer.toString(port));
        PreviewServiceTest.FixtureAdapter.evaluations.set(0);
        try (var preview = new PreviewService(settings)) {
            preview.start();
            for (String invalid : new String[] {
                "{\"expertBean\":\"unknown\",\"operation\":\"boolean\",\"state\":{}}",
                "{\"expertBean\":\"fixture\",\"operation\":\"unknown\",\"state\":{}}",
                "{\"expertBean\":\"fixture\",\"operation\":\"score\",\"state\":\"invoice\"}",
                "{\"expertBean\":\"fixture\",\"operation\":\"score\",\"parameters\":{\"weight\":\"heavy\"},\"state\":\"invoice\"}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"state\":\"invoice\"}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"state\":true}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"state\":{}}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"parameters\":{\"options\":{\"mode\":\"unsupported\"}},\"state\":{\"invoice\":12}}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"parameters\":[],\"state\":{}}",
                "{\"expertBean\":\"fixture\",\"operation\":\"boolean\"}"
            }) {
                var response = send(request(port, "/api/v1/preview", "POST", invalid));
                assertEquals(400, response.statusCode(), invalid + " " + response.body());
                assertEquals(
                        "invalid_request",
                        RuntimeTest.JSON.readTree(response.body()).path("error").asText());
            }
            assertEquals(0, PreviewServiceTest.FixtureAdapter.evaluations.get());
            var valid = send(request(
                    port,
                    "/api/v1/preview",
                    "POST",
                    "{\"expertBean\":\"fixture\",\"operation\":\"boolean\",\"state\":[\"invoice\"]}"));
            assertEquals(200, valid.statusCode(), valid.body());
            assertTrue(RuntimeTest.JSON.readTree(valid.body()).path("value").isBoolean());
            assertEquals(java.util.Map.of(), PreviewServiceTest.FixtureAdapter.parameters);
            var score = send(
                    port, new PreviewService.Request("fixture", "score", java.util.Map.of("weight", 0.5), "invoice"));
            assertEquals(200, score.statusCode(), score.body());
            var scoreResult = RuntimeTest.JSON.readTree(score.body());
            assertEquals("score", scoreResult.path("resultType").asText());
            assertTrue(scoreResult.path("value").isNumber());
            assertEquals(0.75, scoreResult.path("value").asDouble());
            for (var state : java.util.List.of(java.util.List.of("invoice"), java.util.List.of())) {
                var classification =
                        send(port, new PreviewService.Request("fixture", "classification", java.util.Map.of(), state));
                assertEquals(200, classification.statusCode(), classification.body());
                var classificationResult = RuntimeTest.JSON.readTree(classification.body());
                assertEquals(
                        "classification",
                        classificationResult.path("resultType").asText());
                assertTrue(classificationResult.path("value").isArray());
                assertEquals(
                        state.isEmpty() ? 0 : 1,
                        classificationResult.path("value").size());
                if (!state.isEmpty()) {
                    assertEquals(
                            "billing", classificationResult.path("value").get(0).asText());
                }
            }
            for (var malformed : java.util.List.of(
                    new PreviewService.Request(
                            "fixture", "boolean", java.util.Map.of(), java.util.Map.of("malformed", true)),
                    new PreviewService.Request("fixture", "score", java.util.Map.of("weight", 0.5), "malformed"),
                    new PreviewService.Request(
                            "fixture", "classification", java.util.Map.of(), java.util.Map.of("malformed", true)))) {
                var response = send(port, malformed);
                assertEquals(502, response.statusCode(), response.body());
                assertEquals(
                        "evaluation_failed",
                        RuntimeTest.JSON.readTree(response.body()).path("error").asText());
            }
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(int port, PreviewService.Request request) throws Exception {
        return send(request(port, request));
    }

    private static HttpRequest request(int port, PreviewService.Request request) throws Exception {
        return request(port, "/api/v1/preview", "POST", RuntimeTest.JSON.writeValueAsString(request));
    }

    private static HttpRequest request(int port, String path, String method, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
