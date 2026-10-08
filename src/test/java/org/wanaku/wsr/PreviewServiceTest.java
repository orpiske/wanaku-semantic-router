package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Properties;

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
                "message",
                "Select a support action",
                Map.of("billing", "Invoices", "technical", "Bugs", "no_match", "Neither"),
                "typesafe",
                message);
    }

    @Test
    void usesNativeProviderAndNeverLoadsActionRoutes() throws Exception {
        try (var provider = new LocalProvider(0);
                var preview = new PreviewService(providerSettings(provider.port()))) {
            for (String label : new String[] {"billing", "technical", "unmatched"}) {
                var result = preview.classify(request(label));
                assertEquals(label.equals("unmatched") ? "no_match" : label, result.get("label"));
                assertEquals(1.0, ((Map<?, ?>) result.get("diagnostics")).get("confidence"));
            }
            assertEquals(3, provider.evaluations.get());
            assertThrows(Exception.class, () -> preview.classify(request("failure")));
            assertThrows(Exception.class, () -> preview.classify(request("invalid")));
            assertEquals(5, provider.evaluations.get());
        }
    }

    @Test
    void rejectsUnsupportedInputAndExpertBeforeInference() throws Exception {
        try (var provider = new LocalProvider(0);
                var preview = new PreviewService(providerSettings(provider.port()))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preview.classify(new PreviewService.Request(
                            "all", "Instructions", request("billing").criteria(), "typesafe", "billing")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preview.classify(new PreviewService.Request(
                            "message", "Instructions", request("billing").criteria(), "unknown", "billing")));
            assertEquals(0, provider.evaluations.get());
        }
    }
}
