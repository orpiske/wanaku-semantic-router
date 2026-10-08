package org.wanaku.wsr;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Resolve one published Barn route into the existing immutable catalog selection. */
final class SemanticRouteResolver {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private SemanticRouteResolver() {}

    static void resolve(Properties deployment, Map<String, String> environment) throws Exception {
        String name = RuntimeSettings.required(deployment, "wsr.semantic-route");
        if (name.length() > 256 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid semantic route name");
        }
        defaults(deployment);
        var runtime = RuntimeSettings.RuntimeOptions.from(deployment);
        String query = "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        String revision = deployment.getProperty("wsr.catalog.revision");
        if (revision != null) {
            RuntimeSettings.identifier(revision);
            if (revision.equalsIgnoreCase("latest")) {
                throw new IllegalArgumentException("Select a fixed revision");
            }
            query += "&revision=" + URLEncoder.encode(revision, StandardCharsets.UTF_8);
        }
        String base = RuntimeSettings.uri(RuntimeSettings.required(deployment, "wsr.barn.url"))
                .toString();
        URI uri = URI.create(base.replaceAll("/$", "") + "/api/v1/semantic-routers/resolve?" + query);
        var response = Http.exchange(
                Http.request(uri, RuntimeSettings.secret(deployment, "wsr.barn.token-env"))
                        .timeout(runtime.httpTimeout())
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                MAX_RESPONSE_BYTES);
        if (response.statusCode() != 200) {
            throw new IOException("Barn semantic route lookup failed with status " + response.statusCode());
        }
        JsonNode selection = selection(response.body());
        if (!name.equals(text(selection, "name"))) {
            throw new IOException("Barn returned a different semantic route");
        }
        String toolName = RuntimeSettings.identifier(text(selection, "toolName"));
        pin(deployment, "wsr.catalog.name", text(selection, "catalogName"));
        pin(deployment, "wsr.catalog.service", text(selection, "service"));
        pin(deployment, "wsr.catalog.revision", text(selection, "revision"));
        pin(deployment, "wsr.catalog.sha256", text(selection, "sha256").toLowerCase(Locale.ROOT));
        String mainFile = text(selection, "mainFile");
        CatalogLoader.contained(runtime.workDirectory().toAbsolutePath().normalize(), mainFile);
        pin(deployment, "wsr.catalog.main", mainFile);
        // Reuse the ordinary pin validation before enabling a selected expert.
        RuntimeSettings.CatalogReference.from(deployment);
        deployment.putIfAbsent("wsr.forward.name", forwardName(name, toolName));
        expert(deployment, selection.get("expert"), environment);
    }

    private static void defaults(Properties deployment) throws Exception {
        deployment.putIfAbsent("wsr.barn.url", "http://localhost:8180");
        deployment.putIfAbsent("wsr.wanaku.url", "http://localhost:8080");
        if (!deployment.containsKey("wsr.mcp.address")) {
            String bind = deployment.getProperty("wsr.bind", "127.0.0.1");
            if (!loopback(bind)) {
                throw new IllegalArgumentException("A non-loopback bind requires --mcp-address");
            }
            int port = CliConfiguration.port(deployment, "wsr.port", 8090);
            deployment.setProperty("wsr.mcp.address", new URI("http", null, bind, port, "/mcp", null, null).toString());
        }
    }

    private static boolean loopback(String bind) throws IOException {
        if (bind.equalsIgnoreCase("localhost")) {
            return true;
        }
        // Numeric addresses avoid DNS while accepting the complete IPv4 and IPv6 loopback forms.
        if (!bind.matches("[0-9.]+") && !bind.matches("[0-9a-fA-F:]+")) {
            return false;
        }
        return InetAddress.getByName(bind).isLoopbackAddress();
    }

    private static JsonNode selection(byte[] body) throws IOException {
        try {
            JsonNode envelope = JSON.readTree(body);
            if (envelope == null
                    || !envelope.isObject()
                    || (envelope.has("error") && !envelope.get("error").isNull())
                    || !envelope.path("data").isObject()) {
                throw new IOException("Barn did not return a published semantic route");
            }
            return envelope.get("data");
        } catch (JsonProcessingException e) {
            throw new IOException("Barn returned invalid semantic route metadata");
        }
    }

    private static String text(JsonNode object, String field) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Barn semantic route metadata is missing " + field);
        }
        return value.asText();
    }

    private static void pin(Properties deployment, String key, String value) {
        String explicit = deployment.getProperty(key);
        if ("wsr.catalog.sha256".equals(key) && explicit != null) {
            explicit = explicit.toLowerCase(Locale.ROOT);
        }
        if (explicit != null && !explicit.equals(value)) {
            throw new IllegalArgumentException("Catalog pin conflicts with selected publication: " + key);
        }
        deployment.setProperty(key, value);
    }

    private static String forwardName(String name, String toolName) {
        try {
            return RuntimeSettings.identifier(name);
        } catch (IllegalArgumentException e) {
            return toolName;
        }
    }

    private static void expert(Properties deployment, JsonNode expert, Map<String, String> environment)
            throws IOException {
        if (expert == null || expert.isNull()) {
            RuntimeSettings.required(deployment, "wsr.experts");
            return;
        }
        if (!expert.isObject()) {
            throw new IOException("Barn returned invalid published expert metadata");
        }
        String bean = RuntimeSettings.identifier(text(expert, "bean"));
        String dependency = text(expert, "dependency");
        deployment.setProperty("wsr.semantic-route.expert-bean", bean);
        Experts.defaults(deployment, bean, dependency, environment);
    }
}
