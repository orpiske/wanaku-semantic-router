package org.wanaku.wsr;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.camel.semantic.SemanticAuditRecord;
import org.apache.camel.semantic.SemanticAuditSink;
import org.apache.camel.support.service.ServiceSupport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Best-effort, bounded forwarding on Camel's audit dispatcher; acceptance is in memory only. */
public final class WanakuAuditSink extends ServiceSupport implements SemanticAuditSink {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_REQUEST = 64 * 1024;
    private static final int MAX_RESPONSE = 8 * 1024;
    private final AuditSettings settings;
    private final Map<String, String> source;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile String lastFailure = "none";

    public WanakuAuditSink(AuditSettings settings, Map<String, String> source) {
        this.settings = settings;
        this.source = Map.copyOf(source);
    }

    @Override
    public void append(SemanticAuditRecord record) throws IOException, InterruptedException {
        if (!settings.enabled()) {
            return;
        }
        received.incrementAndGet();
        try {
            checkRunning();
            byte[] body = envelope(record);
            if (body.length > MAX_REQUEST) {
                throw new IOException("request_too_large");
            }
            deliver(body);
            delivered.incrementAndGet();
        } catch (InterruptedException e) {
            failure("interrupted");
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            failure(e.getMessage());
            // Never attach provider/transport causes, endpoint URLs, or response bodies.
            throw new IOException(lastFailure);
        }
    }

    private byte[] envelope(SemanticAuditRecord record) throws IOException {
        var forwarded = new LinkedHashMap<>(record.toMap());
        if (forwarded.containsKey("input")) {
            forwarded.remove("input");
            forwarded.put("inputOmitted", "forwarding_policy");
        }
        forwarded.remove("inputRedacted");
        try {
            return JSON.writeValueAsBytes(Map.of("source", source, "record", forwarded));
        } catch (IOException e) {
            throw new IOException("invalid_record");
        }
    }

    private void deliver(byte[] body) throws IOException, InterruptedException {
        HttpRequest request = Http.request(settings.url(), null)
                .timeout(Duration.ofSeconds(1))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        String failure = "transport_failed";
        for (int attempt = 0; attempt < 3; attempt++) {
            checkRunning();
            java.net.http.HttpResponse<byte[]> response = null;
            try {
                response = Http.exchange(request, MAX_RESPONSE);
            } catch (IOException e) {
                failure = "transport_failed";
            }
            if (response != null) {
                int code = response.statusCode();
                if (code == 200) {
                    validateAcknowledgement(response.body());
                    return;
                }
                if (code != 408 && code != 429 && (code < 500 || code > 599)) {
                    throw new IOException("receiver_rejected");
                }
                failure = "receiver_unavailable";
            }
            if (attempt < 2) {
                checkRunning();
                Thread.sleep(100L * (attempt + 1));
                retries.incrementAndGet();
            }
        }
        throw new IOException(failure);
    }

    private static void validateAcknowledgement(byte[] body) throws IOException {
        try {
            var ack = JSON.readTree(body);
            var data = ack == null ? null : ack.get("data");
            if (data == null
                    || !"accepted_in_memory".equals(data.path("status").asText())
                    || !data.path("event_id").isTextual()
                    || data.path("event_id").asText().isBlank()
                    || !data.path("duplicate").isBoolean()
                    || !ack.has("error")
                    || !ack.get("error").isNull()) {
                throw new IOException("invalid_acknowledgement");
            }
        } catch (IOException e) {
            throw new IOException("invalid_acknowledgement");
        }
    }

    private void checkRunning() throws InterruptedException, IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Audit forwarding interrupted");
        }
        if (!isStarted() || isStopping()) {
            throw new IOException("sink_stopped");
        }
    }

    private void failure(String code) {
        failed.incrementAndGet();
        lastFailure = code;
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", settings.enabled(),
                "received", received.get(),
                "delivered", delivered.get(),
                "retries", retries.get(),
                "failed", failed.get(),
                "lastFailure", lastFailure);
    }
}
