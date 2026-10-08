package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.main.Main;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Executes a native Kafka sink with Kafka's test producer, without a broker or inference provider. */
@Timeout(15)
class KafkaSinkTest {
    @TempDir
    Path directory;

    @Test
    void acceptsPackagedKafkaAndExecutesNativeSinkBeforeAcknowledging() throws Exception {
        Path declarations =
                Files.writeString(directory.resolve("dependencies.txt"), "camel:kafka\ncamel:kamelet\ncamel:direct\n");
        Path kamelets = Files.createDirectory(directory.resolve("kamelets"));
        Files.writeString(
                kamelets.resolve("native-kafka-sink.kamelet.yaml"),
                """
                apiVersion: camel.apache.org/v1
                kind: Kamelet
                metadata:
                  name: native-kafka-sink
                  labels:
                    camel.apache.org/kamelet.type: sink
                spec:
                  definition:
                    title: Kafka destination
                    description: Send each incoming message to Kafka.
                    type: object
                    required: [topic, bootstrapServers]
                    properties:
                      topic:
                        type: string
                      bootstrapServers:
                        type: string
                  dependencies:
                    - camel:kafka
                  template:
                    from:
                      uri: kamelet:source
                      steps:
                        - to:
                            uri: kafka:{{topic}}
                            parameters:
                              brokers: "{{bootstrapServers}}"
                """);
        Path route = Files.writeString(
                directory.resolve("dispatch.camel.yaml"),
                """
                - route:
                    id: native-kafka-dispatch
                    from:
                      uri: direct:native-kafka
                      steps:
                        - to:
                            uri: kamelet:native-kafka-sink
                            parameters:
                              topic: support-events
                              bootstrapServers: 127.0.0.1:1
                        - setBody:
                            constant: Sent to Kafka
                """);
        var producer = new MockProducer<String, String>(true, null, new StringSerializer(), new StringSerializer());
        AtomicReference<Properties> clientProperties = new AtomicReference<>();
        try (var dependencies = Dependencies.load(declarations, directory.resolve("resolved"))) {
            var main = new Main();
            try {
                assertSame(Dependencies.class.getClassLoader(), dependencies.classLoader());
                assertFalse(
                        Files.exists(directory.resolve("resolved")), "Packaged Kafka must not use the SDK resolver");
                assertNotNull(dependencies
                        .classLoader()
                        .getResource("META-INF/maven/org.apache.camel/camel-kafka/pom.properties"));
                main.configure().withRoutesIncludePattern(route.toUri().toString());
                main.addProperty(
                        "camel.component.kamelet.location", kamelets.toUri().toString());
                main.addProperty("camel.server.enabled", "false");
                main.init();
                main.getCamelContext().setApplicationContextClassLoader(dependencies.classLoader());
                main.getCamelContext()
                        .getComponent("kafka", KafkaComponent.class)
                        .setKafkaClientFactory(new DefaultKafkaClientFactory() {
                            @Override
                            public Producer<String, String> getProducer(Properties properties) {
                                clientProperties.set(properties);
                                return producer;
                            }
                        });
                main.start();
                assertEquals(
                        ServiceStatus.Started,
                        main.getCamelContext().getRouteController().getRouteStatus("native-kafka-dispatch"));
                try (var template = main.getCamelContext().createProducerTemplate()) {
                    assertEquals(
                            "Sent to Kafka",
                            template.requestBody("direct:native-kafka", "Original support message", String.class));
                    assertEquals("127.0.0.1:1", clientProperties.get().getProperty("bootstrap.servers"));
                    assertEquals(1, producer.history().size());
                    assertEquals("support-events", producer.history().getFirst().topic());
                    assertEquals(
                            "Original support message",
                            producer.history().getFirst().value());
                    producer.sendException = new IllegalStateException("Test producer failure");
                    assertThrows(
                            CamelExecutionException.class,
                            () -> template.requestBody("direct:native-kafka", "Rejected message", String.class));
                    assertEquals(
                            1,
                            producer.history().size(),
                            "A failed send must not produce an acknowledgement or another record");
                }
            } finally {
                main.stop();
            }
        }
        assertTrue(producer.closed(), "Stopping Camel must close the Kafka producer");
    }
}
