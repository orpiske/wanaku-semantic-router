package org.wanaku.wsr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import ai.wanaku.capabilities.sdk.common.serializer.JacksonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ForwardRegistration implements AutoCloseable {
    private final RuntimeSettings.RegistrationSettings registration;
    private final RuntimeSettings.CatalogReference catalog;
    private final Duration httpTimeout;
    private final String catalogCamelBuild;
    private boolean registered;
    private final String instance = java.util.UUID.randomUUID().toString();

    ForwardRegistration(
            RuntimeSettings.RegistrationSettings registration,
            RuntimeSettings.CatalogReference catalog,
            Duration httpTimeout,
            String catalogCamelBuild) {
        this.registration = registration;
        this.catalog = catalog;
        this.httpTimeout = httpTimeout;
        this.catalogCamelBuild = catalogCamelBuild;
    }

    void register() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("wsr.revision", catalog.revision());
        labels.put("wsr.digest", catalog.digest());
        labels.put("wsr.instance", instance);
        if (catalogCamelBuild != null) {
            labels.put("wsr.catalog-camel-build", catalogCamelBuild);
        }
        byte[] body = new JacksonSerializer()
                .serialize(Map.of(
                        "name", registration.forwardName(),
                        "address", registration.advertisedMcp().toString(),
                        "namespace", registration.namespace(),
                        "labels", labels))
                .getBytes(StandardCharsets.UTF_8);
        var request = Http.request(endpoint(""), registration.token())
                .timeout(httpTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        // An accepted POST can lose its response. Claim the attempt before any network operation.
        registered = true;
        var response = mapper.readTree(Http.send(request));
        var data = response.has("data") ? response.get("data") : response;
        if (data.isArray() && data.size() == 1) {
            data = data.get(0);
        }
        if (!data.path("forward").path("available").asBoolean(false)
                || data.path("tools_discovered").asInt() != 1) {
            close();
            throw new IOException("Wanaku did not discover a usable WSR forward");
        }
    }

    private URI endpoint(String suffix) {
        return URI.create(registration.wanaku().toString().replaceAll("/$", "") + "/api/v1/forwards" + suffix);
    }

    @Override
    public void close() throws Exception {
        if (!registered) {
            return;
        }
        var current = Http.exchange(Http.request(endpoint("/" + registration.forwardName()), registration.token())
                .timeout(httpTimeout)
                .GET()
                .build());
        if (current.statusCode() == 404) {
            registered = false;
            return;
        }
        if (current.statusCode() != 200) {
            throw new IOException("Cannot verify forward ownership during shutdown");
        }
        var record = new ObjectMapper().readTree(current.body());
        if (record.has("data")) {
            record = record.get("data");
        }
        if (!instance.equals(record.path("labels").path("wsr.instance").asText())) {
            registered = false;
            return;
        }
        Http.send(Http.request(endpoint("/" + registration.forwardName()), registration.token())
                .timeout(httpTimeout)
                .DELETE()
                .build());
        registered = false;
    }
}
