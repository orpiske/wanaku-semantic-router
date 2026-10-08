package org.wanaku.wsr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ai.wanaku.capabilities.sdk.maven.GAV;
import ai.wanaku.capabilities.sdk.maven.WanakuMavenDownloader;

/** Resolve trusted GAV declarations before Camel starts, retaining the packaged Camel runtime. */
final class Dependencies implements AutoCloseable {
    private final WanakuMavenDownloader downloader;

    private Dependencies(WanakuMavenDownloader downloader) {
        this.downloader = downloader;
    }

    static Dependencies load(Path dependencies, Path directory) throws Exception {
        List<GAV> extra = declarations(dependencies);
        if (extra.isEmpty()) {
            return new Dependencies(null);
        }
        Files.createDirectories(directory);
        WanakuMavenDownloader downloader = new WanakuMavenDownloader(
                List.of(), directory.resolve("repository"), Dependencies.class.getClassLoader());
        try {
            for (Path jar : resolve(downloader, extra, Duration.ofMinutes(2))) {
                if (jar.getFileName().toString().startsWith("camel-")) {
                    throw new IOException("External dependencies cannot replace packaged Camel libraries");
                }
            }
            return new Dependencies(downloader);
        } catch (Exception e) {
            try {
                downloader.close();
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static List<GAV> declarations(Path dependencies) throws IOException {
        List<GAV> extra = new ArrayList<>();
        for (GAV gav : readDeclarations(dependencies)) {
            if (gav.groupId().equals("org.apache.camel")) {
                if (!gav.version().equals(CatalogLoader.CAMEL_VERSION)) {
                    throw new IOException("Mixed Camel dependency versions");
                }
                String descriptor = "META-INF/maven/org.apache.camel/" + gav.artifactId() + "/pom.properties";
                if (Dependencies.class.getClassLoader().getResource(descriptor) == null) {
                    throw new IOException("Camel dependency is absent from packaged distribution: " + gav.artifactId());
                }
            } else {
                if (gav.version().endsWith("SNAPSHOT")
                        || gav.version().equals("LATEST")
                        || gav.version().equals("RELEASE")) {
                    throw new IOException("Expert dependencies require fixed release GAVs");
                }
                extra.add(gav);
            }
        }
        return extra;
    }

    private static List<GAV> readDeclarations(Path dependencies) throws IOException {
        // This local version-default setup duplicates CIC's SDK-based setup because WSR requires
        // unreleased Camel 4.23.0-SNAPSHOT. SDK RuntimeVersionHelper supplies released Camel defaults.
        // Replace this default with RuntimeVersionHelper.getVersions() when the SDK supports WSR's
        // Camel version. Catalog syntax and compatibility checks remain WSR policy.
        List<GAV> declarations = new ArrayList<>();
        Properties versions = new Properties();
        versions.setProperty("org.apache.camel", CatalogLoader.CAMEL_VERSION);
        for (String line : Files.readAllLines(dependencies)) {
            String declaration = line.trim();
            if (declaration.isEmpty() || declaration.startsWith("#")) {
                continue;
            }
            if (declaration.startsWith("mvn:")) {
                declaration = declaration.substring(4);
            }
            if (declaration.startsWith("camel:")) {
                declaration = "org.apache.camel:camel-" + declaration.substring(6);
            }
            if (declaration.equals("org.apache.camel:camel-core")) {
                declaration = "org.apache.camel:camel-core-engine";
            }
            String[] parts = declaration.split(":", -1);
            if (parts.length < 2 || parts.length > 3) {
                throw new IOException("Invalid dependency GAV");
            }
            for (String part : parts) {
                if (!part.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                    throw new IOException("Invalid dependency GAV");
                }
            }
            GAV gav;
            try {
                gav = GAV.parse(declaration, versions);
            } catch (IllegalArgumentException e) {
                throw new IOException("Expert dependencies require fixed release GAVs", e);
            }
            declarations.add(gav);
        }
        return declarations;
    }

    static List<Path> resolve(WanakuMavenDownloader downloader, List<GAV> gavs, Duration timeout) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var resolution = executor.submit(() -> downloader.download(gavs));
        try {
            return resolution.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Dependency resolution timed out", e);
        } catch (ExecutionException e) {
            throw new IOException("Dependency resolution failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            resolution.cancel(true);
            executor.shutdownNow();
        }
    }

    ClassLoader classLoader() {
        return downloader == null ? Dependencies.class.getClassLoader() : downloader.getClassLoader();
    }

    @Override
    public void close() {
        if (downloader != null) {
            downloader.close();
        }
    }
}
