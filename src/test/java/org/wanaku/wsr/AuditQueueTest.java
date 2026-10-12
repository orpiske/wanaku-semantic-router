package org.wanaku.wsr;

import static org.apache.camel.semantic.SemanticEvaluationsBuilder.semanticEvaluations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.console.DevConsole;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticAudit;
import org.apache.camel.semantic.SemanticAuditConfiguration;
import org.apache.camel.semantic.SemanticAuditConsole;
import org.apache.camel.support.DefaultExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

class AuditQueueTest {
    @Test
    void reportsCamelQueueDropsAndDrainsAcceptedRecordsOnShutdown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/audit/semantic", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                var acknowledgement = RuntimeTest.JSON.createObjectNode();
                acknowledgement.putNull("error");
                acknowledgement.set(
                        "data",
                        RuntimeTest.JSON.valueToTree(
                                Map.of("status", "accepted_in_memory", "event_id", "stored-id", "duplicate", false)));
                PreviewService.respond(exchange, 200, acknowledgement);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        var sink = new WanakuAuditSink(
                new AuditSettings(
                        true,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/audit/semantic")),
                Map.of(
                        "instance_id",
                        "instance",
                        "forward_name",
                        "support",
                        "namespace",
                        "default",
                        "catalog_revision",
                        "r1",
                        "catalog_digest",
                        "a".repeat(64)));
        Main main = new Main();
        try {
            main.bind("wanakuAudit", sink);
            main.configure().addRoutesBuilder(new RouteBuilder() {
                @Override
                public void configure() {
                    semanticEvaluations(this)
                            .audit(new SemanticAuditConfiguration(
                                    true, Map.of(), List.of("memory", "wanakuAudit"), "memory", 10, 1))
                            .register();
                }
            });
            main.start();
            var audit = SemanticAudit.get(main.getCamelContext());
            var exchange = new DefaultExchange(main.getCamelContext());
            audit.decision(exchange, Map.of("action", "continue"), List.of());
            assertTrue(entered.await(2, TimeUnit.SECONDS), "First delivery must reach the receiver");
            for (int i = 0; i < 3; i++) {
                audit.decision(exchange, Map.of("action", "continue"), List.of());
            }
            var console = new SemanticAuditConsole();
            console.setCamelContext(main.getCamelContext());
            var response = (Map<?, ?>) console.call(DevConsole.MediaType.JSON, Map.of("limit", 1));
            assertEquals(2L, ((Map<?, ?>) response.get("audit")).get("dropped"));
            release.countDown();
            main.stop();
            assertEquals(2L, sink.status().get("delivered"));
        } finally {
            release.countDown();
            main.stop();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
