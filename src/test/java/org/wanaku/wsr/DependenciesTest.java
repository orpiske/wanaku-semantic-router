package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import ai.wanaku.capabilities.sdk.maven.GAV;
import ai.wanaku.capabilities.sdk.maven.WanakuMavenDownloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependenciesTest {
    @TempDir
    Path directory;

    @Test
    void keepsPackagedCamelOnTheApplicationClassLoader() throws Exception {
        Path declarations = declaration("# packaged components\n\ncamel:core\norg.apache.camel:camel-semantic\n"
                + "mvn:org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT\n");
        try (var dependencies = Dependencies.load(declarations, directory.resolve("resolved"))) {
            assertSame(Dependencies.class.getClassLoader(), dependencies.classLoader());
            assertNotNull(dependencies
                    .classLoader()
                    .getResource("META-INF/maven/org.apache.camel/camel-semantic/pom.properties"));
        }
    }

    @Test
    void rejectsInvalidUnpinnedAndUnpackagedDeclarationsBeforeResolution() throws Exception {
        for (String invalid : List.of(
                "example:expert",
                "example:expert:1.0-SNAPSHOT",
                "example:expert:LATEST",
                "example:expert:RELEASE",
                "example:expert:1.0:classifier",
                "example:expert:[1,2)",
                "example:expert:",
                "example:expert:1.0<",
                "org.apache.camel:camel-semantic:4.22.0",
                "org.apache.camel:camel-absent-test-component")) {
            assertThrows(
                    IOException.class,
                    () -> Dependencies.load(declaration(invalid), directory.resolve("resolved")),
                    invalid);
        }
    }

    @Test
    void mediatesReleaseAndTransitiveDependenciesWithSdkAndClosesItsClassLoader() throws Exception {
        Path resolved = directory.resolve("resolved");
        Path repository = resolved.resolve("repository");
        install(
                repository,
                GAV.parse("example.fixture:expert:1.0"),
                "expert.marker",
                "<dependency><groupId>example.fixture</groupId><artifactId>helper</artifactId><version>1.0</version></dependency>");
        install(repository, GAV.parse("example.fixture:helper:1.0"), "helper.marker", "");
        install(repository, GAV.parse("example.fixture:helper:2.0"), "helper.marker", "");
        ClassLoader loader;
        try (var dependencies = Dependencies.load(
                declaration("mvn:example.fixture:expert:1.0\nexample.fixture:helper:2.0"), resolved)) {
            loader = dependencies.classLoader();
            assertNotNull(loader.getResource("expert.marker"));
            assertNotNull(loader.getResource("helper.marker"));
            try (var helper = loader.getResourceAsStream("helper.marker")) {
                assertNotNull(helper);
                assertEquals("example.fixture:helper:2.0", new String(helper.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertSame(Dependencies.class.getClassLoader(), loader.getParent());
        }
        assertNull(loader.getResource("expert.marker"));
        assertNull(loader.getResource("helper.marker"));
    }

    @Test
    void rejectsTransitiveCamelBeforeExposingDownloadedClassLoader() throws Exception {
        Path resolved = directory.resolve("resolved");
        Path repository = resolved.resolve("repository");
        install(
                repository,
                GAV.parse("example.fixture:expert:1.0"),
                "expert.marker",
                "<dependency><groupId>org.apache.camel</groupId><artifactId>camel-unpackaged-fixture</artifactId><version>4.22.0</version></dependency>");
        install(repository, GAV.parse("org.apache.camel:camel-unpackaged-fixture:4.22.0"), "camel.marker", "");
        IOException failure = assertThrows(
                IOException.class, () -> Dependencies.load(declaration("example.fixture:expert:1.0"), resolved));
        assertEquals("External dependencies cannot replace packaged Camel libraries", failure.getMessage());
    }

    @Test
    void boundsSdkResolutionAndInterruptsTheResolver() throws Exception {
        try (var downloader = new BlockingDownloader(directory.resolve("repository"))) {
            long started = System.nanoTime();
            IOException failure = assertThrows(
                    IOException.class,
                    () -> Dependencies.resolve(
                            downloader, List.of(GAV.parse("example:expert:1.0")), Duration.ofMillis(200)));
            assertEquals("Dependency resolution timed out", failure.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertTrue(downloader.interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    private Path declaration(String contents) throws IOException {
        return Files.writeString(directory.resolve("dependencies.txt"), contents);
    }

    private static void install(Path repository, GAV gav, String resource, String dependencies) throws IOException {
        Path artifactDirectory = Files.createDirectories(repository
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version()));
        String base = gav.artifactId() + "-" + gav.version();
        Files.writeString(
                artifactDirectory.resolve(base + ".pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>" + gav.groupId() + "</groupId><artifactId>"
                        + gav.artifactId()
                        + "</artifactId><version>" + gav.version() + "</version><dependencies>" + dependencies
                        + "</dependencies></project>");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifactDirectory.resolve(base + ".jar")))) {
            jar.putNextEntry(new JarEntry(resource));
            jar.write(gav.toString().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static final class BlockingDownloader extends WanakuMavenDownloader {
        private final CountDownLatch interrupted = new CountDownLatch(1);

        private BlockingDownloader(Path repository) {
            super(List.of(), repository);
        }

        @Override
        public List<Path> download(List<GAV> gavs) {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }
}
