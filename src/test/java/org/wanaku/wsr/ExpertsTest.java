package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticAdapter;
import org.apache.camel.semantic.SemanticEvaluation;
import org.apache.camel.semantic.SemanticResult;
import org.apache.camel.support.service.ServiceSupport;

import org.junit.jupiter.api.Test;

class ExpertsTest {
    public static final class ConfiguredAdapter extends ServiceSupport implements SemanticAdapter {
        private int limit;
        private String location;
        private boolean startedWithConfiguration;

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        @Override
        protected void doStart() {
            startedWithConfiguration = limit == 7 && "fixture-model".equals(location);
        }

        @Override
        public void validate(SemanticEvaluation evaluation) {}

        @Override
        public SemanticResult evaluate(SemanticEvaluation evaluation, Object state) {
            return null;
        }
    }

    private Properties settings() {
        Properties settings = new Properties();
        Map.of(
                        "wsr.experts",
                        "fixture",
                        "wsr.expert.fixture.class",
                        ConfiguredAdapter.class.getName(),
                        "wsr.expert.fixture.properties.limit",
                        "7",
                        "wsr.expert.fixture.properties.location",
                        "{{sys:wsr.test.model}}")
                .forEach(settings::setProperty);
        return settings;
    }

    @Test
    void bindsBeforeLifecycle() throws Exception {
        System.setProperty("wsr.test.model", "fixture-model");
        Main main = new Main();
        try {
            Experts.configure(main, settings(), "fixture");
            ConfiguredAdapter adapter =
                    main.getCamelContext().getRegistry().lookupByNameAndType("fixture", ConfiguredAdapter.class);
            assertEquals(7, adapter.limit);
            assertEquals("fixture-model", adapter.location);
            main.start();
            assertTrue(adapter.startedWithConfiguration);
        } finally {
            main.stop();
            System.clearProperty("wsr.test.model");
        }
    }

    @Test
    void rejectsMissingGuard() throws Exception {
        Properties settings = settings();
        settings.setProperty("wsr.semantic-route.guard-bean", "missing");
        Main main = new Main();
        try {
            assertThrows(IllegalArgumentException.class, () -> Experts.configure(main, settings, "fixture"));
        } finally {
            main.stop();
        }
    }

    @Test
    void sanitizesInvalidPropertyValues() throws Exception {
        Properties settings = settings();
        settings.setProperty("wsr.expert.fixture.properties.limit", "secret-value");
        Main main = new Main();
        try {
            Exception failure =
                    assertThrows(IllegalArgumentException.class, () -> Experts.configure(main, settings, "fixture"));
            assertEquals("Unable to configure expert deployment properties", failure.getMessage());
            assertNull(failure.getCause());
        } finally {
            main.stop();
        }
    }

    @Test
    void packagesWolfDefender() {
        assertNotNull(getClass()
                .getClassLoader()
                .getResource("META-INF/maven/org.apache.camel/camel-wolf-defender/pom.properties"));
    }
}
