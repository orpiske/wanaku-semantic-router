package org.wanaku.wsr;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/** Deployment settings contain service credentials; catalog artifacts never do. */
public record RuntimeSettings(
        CatalogSettings catalog, RegistrationSettings registration, RuntimeOptions runtime, Properties deployment) {

    /** A fixed service and artifact selected from a catalog. */
    public record CatalogReference(String name, String service, String revision, String digest, String main) {
        static CatalogReference from(Properties p) {
            String digest = required(p, "wsr.catalog.sha256").toLowerCase();
            if (!digest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Catalog digest must be SHA-256");
            }
            String revision = identifier(required(p, "wsr.catalog.revision"));
            if (revision.equalsIgnoreCase("latest")) {
                throw new IllegalArgumentException("Select a fixed revision");
            }
            return new CatalogReference(
                    identifier(required(p, "wsr.catalog.name")),
                    identifier(required(p, "wsr.catalog.service")),
                    revision,
                    digest,
                    p.getProperty("wsr.catalog.main"));
        }
    }

    /** Barn connection authority and the immutable catalog selection. */
    public record CatalogSettings(URI barn, String token, CatalogReference reference) {
        static CatalogSettings from(Properties p) {
            return new CatalogSettings(
                    uri(required(p, "wsr.barn.url")), secret(p, "wsr.barn.token-env"), CatalogReference.from(p));
        }

        @Override
        public String toString() {
            return "CatalogSettings[reference=" + reference + ", credentials=redacted]";
        }
    }

    /** Wanaku management authority and the service endpoint advertised to agents. */
    public record RegistrationSettings(
            URI wanaku, String token, String forwardName, String namespace, URI advertisedMcp) {
        static RegistrationSettings from(Properties p) {
            return new RegistrationSettings(
                    uri(required(p, "wsr.wanaku.url")),
                    secret(p, "wsr.wanaku.token-env"),
                    identifier(required(p, "wsr.forward.name")),
                    p.getProperty("wsr.forward.namespace", "default"),
                    uri(required(p, "wsr.mcp.address")));
        }

        @Override
        public String toString() {
            return "RegistrationSettings[forwardName=" + forwardName + ", namespace=" + namespace
                    + ", credentials=redacted]";
        }
    }

    /** Local listener, private extraction location, and transport completion limits. */
    public record RuntimeOptions(int port, String bind, Path workDirectory, Duration httpTimeout) {
        static RuntimeOptions from(Properties p) {
            long timeout = Long.parseLong(p.getProperty("wsr.http.timeout-ms", "30000"));
            if (timeout < 1 || timeout > 120000) {
                throw new IllegalArgumentException("Invalid HTTP completion timeout");
            }
            int port = Integer.parseInt(p.getProperty("wsr.port", "8090"));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid MCP port");
            }
            return new RuntimeOptions(
                    port,
                    p.getProperty("wsr.bind", "127.0.0.1"),
                    Path.of(p.getProperty("wsr.work-directory", System.getProperty("java.io.tmpdir"))),
                    Duration.ofMillis(timeout));
        }
    }

    public static RuntimeSettings from(Properties p) {
        return new RuntimeSettings(CatalogSettings.from(p), RegistrationSettings.from(p), RuntimeOptions.from(p), p);
    }

    @Override
    public String toString() {
        return "RuntimeSettings[catalog=" + catalog + ", registration=" + registration + ", runtime=" + runtime
                + ", deployment=redacted]";
    }

    static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing setting: " + key);
        }
        return value;
    }

    static String identifier(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid identifier");
        }
        return value;
    }

    static URI uri(String value) {
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid service URL");
        }
        return uri;
    }

    static String secret(Properties p, String key) {
        String env = p.getProperty(key);
        if (env == null) {
            return null;
        }
        String value = System.getenv(env);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing credential environment variable: " + env);
        }
        return value;
    }
}
