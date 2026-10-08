package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuntimeSettingsTest {
    private Properties deployment() {
        Properties properties = new Properties();
        properties.setProperty("wsr.barn.url", "https://barn.example");
        properties.setProperty("wsr.catalog.name", "support-catalog");
        properties.setProperty("wsr.catalog.service", "support");
        properties.setProperty("wsr.catalog.revision", "revision-1");
        properties.setProperty("wsr.catalog.sha256", "A".repeat(64));
        properties.setProperty("wsr.wanaku.url", "https://wanaku.example");
        properties.setProperty("wsr.forward.name", "support-forward");
        properties.setProperty("wsr.mcp.address", "https://wsr.example/mcp");
        return properties;
    }

    @Test
    void groupsCatalogAndServiceSettingsWithoutChangingDeploymentDefaults() {
        Properties deployment = deployment();
        RuntimeSettings settings = RuntimeSettings.from(deployment);

        assertEquals(URI.create("https://barn.example"), settings.catalog().barn());
        assertEquals("support-catalog", settings.catalog().reference().name());
        assertEquals("support", settings.catalog().reference().service());
        assertEquals("revision-1", settings.catalog().reference().revision());
        assertEquals("a".repeat(64), settings.catalog().reference().digest());
        assertNull(settings.catalog().reference().main());
        assertNull(settings.catalog().token());
        assertEquals(
                URI.create("https://wanaku.example"), settings.registration().wanaku());
        assertEquals("support-forward", settings.registration().forwardName());
        assertEquals("default", settings.registration().namespace());
        assertEquals(
                URI.create("https://wsr.example/mcp"), settings.registration().advertisedMcp());
        assertNull(settings.registration().token());
        assertEquals(8090, settings.runtime().port());
        assertEquals("127.0.0.1", settings.runtime().bind());
        assertEquals(
                Path.of(System.getProperty("java.io.tmpdir")),
                settings.runtime().workDirectory());
        assertEquals(Duration.ofSeconds(30), settings.runtime().httpTimeout());
        assertSame(deployment, settings.deployment());
    }

    @Test
    void appliesExplicitCatalogSelectionListenerAndTransportSettings() {
        Properties deployment = deployment();
        deployment.setProperty("wsr.catalog.main", "support/main.camel.yaml");
        deployment.setProperty("wsr.forward.namespace", "support-team");
        deployment.setProperty("wsr.port", "12345");
        deployment.setProperty("wsr.bind", "0.0.0.0");
        deployment.setProperty("wsr.work-directory", "private-catalogs");
        deployment.setProperty("wsr.http.timeout-ms", "7500");

        RuntimeSettings settings = RuntimeSettings.from(deployment);
        assertEquals("support/main.camel.yaml", settings.catalog().reference().main());
        assertEquals("support-team", settings.registration().namespace());
        assertEquals(12345, settings.runtime().port());
        assertEquals("0.0.0.0", settings.runtime().bind());
        assertEquals(Path.of("private-catalogs"), settings.runtime().workDirectory());
        assertEquals(Duration.ofMillis(7500), settings.runtime().httpTimeout());
    }

    @ParameterizedTest
    @CsvSource({
        "wsr.catalog.sha256,invalid",
        "wsr.catalog.revision,latest",
        "wsr.catalog.service,../support",
        "wsr.barn.url,ftp://barn.example",
        "wsr.wanaku.url,https://user:password@wanaku.example",
        "wsr.mcp.address,https://wsr.example/mcp#fragment",
        "wsr.port,0",
        "wsr.port,65536",
        "wsr.http.timeout-ms,0",
        "wsr.http.timeout-ms,120001"
    })
    void preservesDeploymentValidation(String name, String value) {
        Properties deployment = deployment();
        deployment.setProperty(name, value);
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(deployment));
    }

    @Test
    void diagnosticsDoNotExposeServiceCredentialsOrExpertDeploymentSecrets() {
        Properties deployment = deployment();
        deployment.setProperty("camel.component.typesafe-ai.api-key", "private-provider-key");
        RuntimeSettings parsed = RuntimeSettings.from(deployment);
        RuntimeSettings.CatalogSettings catalog = new RuntimeSettings.CatalogSettings(
                URI.create("https://barn.example?token=private-url-token"),
                "private-barn-token",
                parsed.catalog().reference());
        RuntimeSettings.RegistrationSettings registration = new RuntimeSettings.RegistrationSettings(
                URI.create("https://wanaku.example?token=private-url-token"),
                "private-wanaku-token",
                "support-forward",
                "default",
                URI.create("https://wsr.example/mcp"));
        RuntimeSettings settings = new RuntimeSettings(catalog, registration, parsed.runtime(), deployment);

        for (String diagnostic : new String[] {catalog.toString(), registration.toString(), settings.toString()}) {
            assertFalse(diagnostic.contains("private-barn-token"));
            assertFalse(diagnostic.contains("private-wanaku-token"));
            assertFalse(diagnostic.contains("private-provider-key"));
            assertFalse(diagnostic.contains("private-url-token"));
        }
    }
}
