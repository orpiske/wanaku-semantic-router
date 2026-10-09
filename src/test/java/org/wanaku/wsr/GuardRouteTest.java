package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticAdapter;
import org.apache.camel.semantic.SemanticEvaluation;
import org.apache.camel.semantic.SemanticExpert;
import org.apache.camel.semantic.SemanticOperation;
import org.apache.camel.semantic.SemanticResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardRouteTest {
    @TempDir
    Path directory;

    @SemanticExpert(
            name = "fixture",
            description = "Guard fixture",
            provider = "fixture",
            artifactId = "fixture",
            operations = {
                @SemanticOperation(
                        name = "boolean",
                        description = "Reject unsafe requests",
                        inputTypes = SemanticExpert.InputType.TEXT,
                        inputRequirements = "Text",
                        resultType = SemanticExpert.ResultType.BOOLEAN,
                        resultMeaning = "Rejected"),
                @SemanticOperation(
                        name = "choice",
                        description = "Dispatch",
                        inputTypes = SemanticExpert.InputType.TEXT,
                        inputRequirements = "Text",
                        resultType = SemanticExpert.ResultType.CHOICE,
                        resultMeaning = "Destination")
            })
    public static final class Adapter implements SemanticAdapter {
        static final AtomicInteger guards = new AtomicInteger();
        static final AtomicInteger classifications = new AtomicInteger();

        @Override
        public void validate(SemanticEvaluation evaluation) {}

        @Override
        public SemanticResult evaluate(SemanticEvaluation evaluation, Object state) {
            if (evaluation.getOperation().equals("boolean")) {
                guards.incrementAndGet();
                if (state.equals("failure")) throw new IllegalStateException("fixture failure");
                return new SemanticResult(state.equals("blocked"), null, Map.of(), null, Map.of());
            }
            classifications.incrementAndGet();
            return new SemanticResult("billing", null, Map.of(), null, Map.of());
        }
    }

    @Test
    void guardStopsRejectedAndFailedRequestsBeforeClassification() throws Exception {
        for (String input : new String[] {"blocked", "failure", "accepted"}) {
            Adapter.guards.set(0);
            Adapter.classifications.set(0);
            AtomicInteger destinations = new AtomicInteger();
            Path route = directory.resolve("guard.camel.yaml");
            Files.writeString(
                    route,
                    """
                - semantic:
                    evaluation:
                      guard:
                        operation: boolean
                        expert: guardExpert
                        state: "${body}"
                      department:
                        operation: choice
                        expert: classifierExpert
                        state: "${body}"
                - route:
                    from:
                      uri: direct:guarded
                      steps:
                        - setProperty:
                            name: guardResult
                            expression:
                              language:
                                language: semantic
                                expression: ref:guard
                        - choice:
                            when:
                              - simple: "${exchangeProperty.guardResult} == true"
                                steps:
                                  - throwException:
                                      exceptionType: java.lang.IllegalStateException
                                      message: Request rejected by semantic guard
                        - setProperty:
                            name: department
                            expression:
                              language:
                                language: semantic
                                expression: ref:department
                        - choice:
                            when:
                              - simple: "${exchangeProperty.department} == 'billing'"
                                steps:
                                  - to: direct:destination
                """);
            Main main = new Main();
            try {
                main.configure().withRoutesIncludePattern(route.toUri().toString());
                Properties settings = new Properties();
                settings.setProperty("wsr.experts", "guardExpert,classifierExpert");
                settings.setProperty("wsr.expert.guardExpert.class", Adapter.class.getName());
                settings.setProperty("wsr.expert.classifierExpert.class", Adapter.class.getName());
                settings.setProperty("wsr.semantic-route.guard-bean", "guardExpert");
                Experts.configure(main, settings, "classifierExpert");
                main.getCamelContext().addRoutes(new org.apache.camel.builder.RouteBuilder() {
                    @Override
                    public void configure() {
                        from("direct:destination").process(exchange -> destinations.incrementAndGet());
                    }
                });
                main.start();
                try (var producer = main.getCamelContext().createProducerTemplate()) {
                    var response = producer.request(
                            "direct:guarded", exchange -> exchange.getMessage().setBody(input));
                    if (!input.equals("accepted")) assertNotNull(response.getException());
                    if (input.equals("blocked"))
                        assertEquals(
                                "Request rejected by semantic guard",
                                response.getException().getMessage());
                }
                assertEquals(1, Adapter.guards.get());
                assertEquals(input.equals("accepted") ? 1 : 0, Adapter.classifications.get());
                assertEquals(input.equals("accepted") ? 1 : 0, destinations.get());
            } finally {
                main.stop();
            }
        }
    }
}
