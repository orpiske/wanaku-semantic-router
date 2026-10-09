package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.semantic.SemanticAdapter;
import org.apache.camel.semantic.SemanticEvaluation;
import org.apache.camel.semantic.SemanticExpert;
import org.apache.camel.semantic.SemanticOperation;
import org.apache.camel.semantic.SemanticParameter;
import org.apache.camel.semantic.SemanticResult;

import org.junit.jupiter.api.Test;

class PreviewServiceTest {
    static Properties providerSettings(int port) {
        Properties settings = new Properties();
        settings.setProperty("wsr.experts", "typesafe");
        settings.setProperty(
                "wsr.expert.typesafe.class", "org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter");
        settings.setProperty("camel.component.typesafe-ai.base-url", "http://127.0.0.1:" + port);
        settings.setProperty("camel.component.typesafe-ai.api-key", "fixture-key");
        settings.setProperty("camel.component.typesafe-ai.model", "fixture");
        settings.setProperty("camel.component.typesafe-ai.request-timeout", "1000");
        settings.setProperty("camel.component.typesafe-ai.max-concurrent-requests", "2");
        return settings;
    }

    static PreviewService.Request request(String message) {
        return new PreviewService.Request(
                "typesafe",
                "choice",
                Map.of(
                        "instructions",
                        "Select a support action",
                        "criteria",
                        Map.of("billing", "Invoices", "technical", "Bugs", "no_match", "Neither")),
                message);
    }

    static Properties fixtureSettings() {
        Properties settings = new Properties();
        settings.setProperty("wsr.experts", "fixture");
        settings.setProperty("wsr.expert.fixture.class", FixtureAdapter.class.getName());
        return settings;
    }

    @SemanticExpert(
            name = "fixture",
            description = "Deterministic preview fixture",
            provider = "fixture",
            artifactId = "fixture",
            operations = {
                @SemanticOperation(
                        name = "boolean",
                        description = "Approve structured input",
                        inputTypes = SemanticExpert.InputType.STRUCTURED,
                        inputRequirements = "Map or list",
                        resultType = SemanticExpert.ResultType.BOOLEAN,
                        resultMeaning = "Approved",
                        probability = true,
                        probabilityMeaning = "Approval probability",
                        parameters =
                                @SemanticParameter(
                                        name = "options",
                                        description = "Literal options",
                                        type = Map.class,
                                        omission = "Use empty options")),
                @SemanticOperation(
                        name = "score",
                        description = "Score text",
                        inputTypes = SemanticExpert.InputType.TEXT,
                        inputRequirements = "Text",
                        resultType = SemanticExpert.ResultType.SCORE,
                        resultMeaning = "Score",
                        minimum = 0,
                        maximum = 1,
                        confidence = true,
                        confidenceMeaning = "Reliability",
                        parameters =
                                @SemanticParameter(
                                        name = "weight",
                                        description = "Weight",
                                        type = Number.class,
                                        required = true,
                                        minimum = 0,
                                        maximum = 1)),
                @SemanticOperation(
                        name = "classification",
                        description = "Classify structured input",
                        inputTypes = SemanticExpert.InputType.STRUCTURED,
                        inputRequirements = "Map or list",
                        resultType = SemanticExpert.ResultType.CLASSIFICATION,
                        resultMeaning = "Matching labels",
                        labels = {"billing", "technical"},
                        probabilities = true,
                        probabilityMeaning = "Label probabilities")
            })
    public static final class FixtureAdapter implements SemanticAdapter {
        static final AtomicInteger evaluations = new AtomicInteger();
        static volatile Map<String, Object> parameters;

        @Override
        public void validate(SemanticEvaluation evaluation) {
            Object options = evaluation.getParameters().get("options");
            if (options instanceof Map<?, ?> map && "unsupported".equals(map.get("mode"))) {
                throw new IllegalArgumentException("Unsupported fixture policy");
            }
        }

        @Override
        public void validateInput(SemanticEvaluation evaluation, Object state) {
            if ("boolean".equals(evaluation.getOperation())
                    && state instanceof Map<?, ?> map
                    && !map.containsKey("invoice")
                    && !map.containsKey("malformed")) {
                throw new IllegalArgumentException("Fixture approval requires invoice data");
            }
        }

        @Override
        public SemanticResult evaluate(SemanticEvaluation evaluation, Object state) {
            evaluations.incrementAndGet();
            parameters = evaluation.getParameters();
            if ("malformed".equals(state) || state.equals(Map.of("malformed", true))) {
                return new SemanticResult("wrong type", null, Map.of(), null, Map.of());
            }
            return switch (evaluation.getOperation()) {
                case "boolean" -> new SemanticResult(true, 0.9, Map.of(), null, Map.of("secret", "not exposed"));
                case "score" -> new SemanticResult(0.75, null, Map.of(), 0.8, Map.of());
                case "classification" ->
                    new SemanticResult(
                            state.equals(List.of()) ? Set.of() : Set.of("billing"),
                            null,
                            Map.of("billing", 0.7),
                            null,
                            Map.of());
                default -> throw new IllegalArgumentException("Unsupported fixture operation");
            };
        }
    }

    @Test
    void usesNativeProviderAndNeverLoadsActionRoutes() throws Exception {
        try (var provider = new LocalProvider(0);
                var preview = new PreviewService(providerSettings(provider.port()))) {
            for (String label : new String[] {"billing", "technical", "unmatched"}) {
                var result = preview.evaluate(request(label));
                assertEquals("choice", result.get("resultType"));
                assertEquals(label.equals("unmatched") ? "no_match" : label, result.get("value"));
                assertEquals(1.0, ((Map<?, ?>) result.get("diagnostics")).get("confidence"));
                var probabilities = (Map<?, ?>) ((Map<?, ?>) result.get("diagnostics")).get("probabilities");
                assertEquals(1.0, probabilities.get(result.get("value")));
                assertEquals(Set.of("billing", "technical", "no_match"), probabilities.keySet());
            }
            assertEquals(3, provider.evaluations.get());
            assertThrows(Exception.class, () -> preview.evaluate(request("failure")));
            assertThrows(Exception.class, () -> preview.evaluate(request("invalid")));
            assertEquals(5, provider.evaluations.get());
        }
    }

    @Test
    void preservesTypedResultsStructuredInputLiteralParametersAndExpertDefaults() throws Exception {
        try (var preview = new PreviewService(fixtureSettings())) {
            var parameters = Map.<String, Object>of(
                    "options", Map.of("literal", "${body}", "nested", List.of(Map.of("enabled", true))));
            var approved = preview.evaluate(
                    new PreviewService.Request("fixture", "boolean", parameters, Map.of("invoice", 12)));
            assertEquals("boolean", approved.get("resultType"));
            assertEquals(true, approved.get("value"));
            assertEquals(Map.of("probability", 0.9), approved.get("diagnostics"));
            assertEquals(parameters, FixtureAdapter.parameters);
            preview.evaluate(new PreviewService.Request("fixture", "boolean", null, List.of("invoice")));
            assertEquals(Map.of(), FixtureAdapter.parameters, "Omitted parameters remain empty for expert defaults");
            var score =
                    preview.evaluate(new PreviewService.Request("fixture", "score", Map.of("weight", 0.5), "invoice"));
            assertEquals("score", score.get("resultType"));
            assertEquals(0.75, score.get("value"));
            assertEquals(Map.of("confidence", 0.8), score.get("diagnostics"));
            var labels = preview.evaluate(
                    new PreviewService.Request("fixture", "classification", Map.of(), List.of("invoice")));
            assertEquals("classification", labels.get("resultType"));
            assertEquals(Set.of("billing"), labels.get("value"));
            assertEquals(Map.of("probabilities", Map.of("billing", 0.7)), labels.get("diagnostics"));
            assertEquals(
                    Set.of(),
                    preview.evaluate(new PreviewService.Request("fixture", "classification", Map.of(), List.of()))
                            .get("value"));
        }
    }

    @Test
    void rejectsInvalidExpertOperationParametersAndInputBeforeInference() throws Exception {
        FixtureAdapter.evaluations.set(0);
        try (var preview = new PreviewService(fixtureSettings())) {
            for (var invalid : List.of(
                    new PreviewService.Request("unknown", "boolean", Map.of(), Map.of()),
                    new PreviewService.Request("fixture", "unknown", Map.of(), "text"),
                    new PreviewService.Request("fixture", "score", Map.of(), "text"),
                    new PreviewService.Request("fixture", "score", Map.of("weight", "heavy"), "text"),
                    new PreviewService.Request("fixture", "score", Map.of("weight", 2), "text"),
                    new PreviewService.Request("fixture", "boolean", Map.of("unexpected", true), Map.of()),
                    new PreviewService.Request("fixture", "boolean", Map.of(), "text"))) {
                assertThrows(IllegalArgumentException.class, () -> preview.evaluate(invalid));
            }
            assertEquals(0, FixtureAdapter.evaluations.get());
        }
        try (var provider = new LocalProvider(0);
                var preview = new PreviewService(providerSettings(provider.port()))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preview.evaluate(new PreviewService.Request(
                            "typesafe", "choice", request("billing").parameters(), 123)));
            assertEquals(0, provider.evaluations.get());
        }
    }
}
