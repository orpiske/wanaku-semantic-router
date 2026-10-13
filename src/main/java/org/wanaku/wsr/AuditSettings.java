package org.wanaku.wsr;

import java.net.URI;

/** Deployment-owned semantic audit capture and forwarding settings. */
public record AuditSettings(boolean enabled, URI url) {
    public static AuditSettings from(RuntimeSettings settings) {
        String enabled = settings.deployment().getProperty("wsr.audit.enabled", "true");
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException("Invalid setting: wsr.audit.enabled");
        }
        URI wanaku = settings.registration().wanaku();
        String origin = wanaku.getScheme() + "://" + wanaku.getRawAuthority();
        return new AuditSettings(
                Boolean.parseBoolean(enabled),
                RuntimeSettings.uri(
                        settings.deployment().getProperty("wsr.audit.url", origin + "/api/v1/audit/semantic")));
    }
}
