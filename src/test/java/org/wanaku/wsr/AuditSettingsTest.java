package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class AuditSettingsTest {
    @Test
    void defaultsToWanakuOriginWithoutInheritedPathQueryOrCredentials() {
        var settings = AuditSettings.from(runtime(new Properties()));
        assertTrue(settings.enabled());
        assertEquals(URI.create("https://wanaku.example:8443/api/v1/audit/semantic"), settings.url());
    }

    @Test
    void allowsExplicitDisableAndValidatedEndpoint() {
        var p = new Properties();
        p.setProperty("wsr.audit.enabled", "false");
        p.setProperty("wsr.audit.url", "http://audit.example/events");
        var settings = AuditSettings.from(runtime(p));
        assertFalse(settings.enabled());
        assertEquals(URI.create("http://audit.example/events"), settings.url());
        for (String invalid : new String[] {"yes", "TRUE", "", " false"}) {
            p.setProperty("wsr.audit.enabled", invalid);
            assertThrows(IllegalArgumentException.class, () -> AuditSettings.from(runtime(p)));
        }
        p.setProperty("wsr.audit.enabled", "true");
        for (String invalid : new String[] {"file:/tmp/events", "http://secret@host/events", "http://host/#fragment"}) {
            p.setProperty("wsr.audit.url", invalid);
            assertThrows(IllegalArgumentException.class, () -> AuditSettings.from(runtime(p)));
        }
    }

    private static RuntimeSettings runtime(Properties properties) {
        var registration = new RuntimeSettings.RegistrationSettings(
                URI.create("https://wanaku.example:8443/management?token=ignored"),
                "secret",
                "support",
                "default",
                null);
        return new RuntimeSettings(null, registration, null, properties);
    }
}
