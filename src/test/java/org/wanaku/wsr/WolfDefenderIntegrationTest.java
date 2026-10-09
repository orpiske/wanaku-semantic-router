package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** Opt-in native model inference; CI never fetches model files. */
class WolfDefenderIntegrationTest {
    @Test
    void evaluatesProvisionedLocalModel() throws Exception {
        String modelDirectory = System.getenv("WOLF_MODEL_DIRECTORY");
        assumeTrue(modelDirectory != null && !modelDirectory.isBlank(), "Provision WOLF_MODEL_DIRECTORY explicitly");
        Properties settings = new Properties();
        Map.of(
                        "wsr.experts",
                        "wolf",
                        "wsr.expert.wolf.class",
                        "org.apache.camel.component.wolfdefender.WolfDefenderSemanticAdapter",
                        "wsr.expert.wolf.properties.modelDirectory",
                        "{{env:WOLF_MODEL_DIRECTORY}}")
                .forEach(settings::setProperty);
        try (var service = new PreviewService(settings)) {
            var result = service.evaluate(
                    new PreviewService.Request("wolf", "injection", Map.of(), "Please help me find my invoice."));
            assertEquals("boolean", result.get("resultType"));
            assertTrue(result.get("value") instanceof Boolean);
        }
    }
}
