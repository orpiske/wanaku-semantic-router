package org.wanaku.wsr;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.TreeMap;

/** Snapshots selected local resources into the runtime's private work directory. */
final class LocalArtifacts {
    private LocalArtifacts() {}

    static final class InvalidInput extends IllegalArgumentException {
        InvalidInput(String message) {
            super(message);
        }
    }

    static void validateSelection(Properties deployment) {
        if (!deployment.containsKey("wsr.routes-ref")) {
            if (deployment.containsKey("wsr.dependencies")) {
                throw new InvalidInput("--dependencies requires --routes-ref");
            }
            return;
        }
        RuntimeSettings.required(deployment, "wsr.routes-ref");
        for (String key : deployment.stringPropertyNames()) {
            if (key.equals("wsr.semantic-route") || key.startsWith("wsr.catalog.")) {
                throw new InvalidInput(
                        "--routes-ref cannot be combined with --semantic-route or catalog selection settings");
            }
        }
        path(deployment.getProperty("wsr.routes-ref"));
        if (deployment.containsKey("wsr.dependencies")) {
            path(RuntimeSettings.required(deployment, "wsr.dependencies"));
        }
    }

    static Path path(String value) {
        try {
            if (value.startsWith("file:")) {
                return Path.of(URI.create(value)).toAbsolutePath().normalize();
            }
            if (value.contains("://")) {
                throw new IllegalArgumentException("Only local file references are supported");
            }
            return Path.of(value).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            throw new InvalidInput("Expected a local file path or file URI for --routes-ref or --dependencies");
        }
    }

    static CatalogLoader.Catalog load(Properties deployment, RuntimeSettings.RuntimeOptions runtime) throws Exception {
        try {
            return snapshotResources(deployment, runtime);
        } catch (IOException e) {
            throw new InvalidInput(
                    "Cannot load local artifacts. Check adjacent semantic-router.properties, compatible contract.version/camel.version/input.profile, and its main, dependencies, configuration and kamelets resources.");
        } catch (IllegalArgumentException e) {
            if (e instanceof InvalidInput input) {
                throw input;
            }
            throw new InvalidInput(
                    "Invalid local semantic manifest. Check required resource references, expert.bean, tool.name and optional guard settings.");
        }
    }

    private static CatalogLoader.Catalog snapshotResources(
            Properties deployment, RuntimeSettings.RuntimeOptions runtime) throws Exception {
        Path main = path(RuntimeSettings.required(deployment, "wsr.routes-ref"));
        Path manifestPath = main.getParent().resolve("semantic-router.properties");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Local route requires adjacent semantic-router.properties");
        }
        Properties manifest = CatalogLoader.properties(manifestPath);
        String mainReference = RuntimeSettings.required(manifest, "main");
        Path relativeMain = Path.of(mainReference);
        Path sourceRoot = main;
        for (int index = 0; index < relativeMain.getNameCount(); index++) {
            sourceRoot = sourceRoot.getParent();
            if (sourceRoot == null) {
                throw new IOException("Local main reference does not match route path");
            }
        }
        if (!CatalogLoader.contained(sourceRoot, mainReference).equals(main)) {
            throw new IOException("Local main reference does not match route path");
        }
        String dependencyReference = RuntimeSettings.required(manifest, "dependencies");
        Path dependencies = deployment.containsKey("wsr.dependencies")
                ? path(deployment.getProperty("wsr.dependencies"))
                : CatalogLoader.contained(sourceRoot, dependencyReference);
        // Validate the original contract before copying; local artifacts need no Barn index or revision pin.
        CatalogLoader.validateResources(sourceRoot, main, dependencies, manifest, null);
        TreeMap<String, Path> resources = new TreeMap<>();
        resources.put(mainReference, main);
        resources.put(sourceRoot.relativize(manifestPath).toString(), manifestPath);
        resources.put(dependencyReference, dependencies);
        String configuration = RuntimeSettings.required(manifest, "configuration");
        resources.put(configuration, CatalogLoader.contained(sourceRoot, configuration));
        for (String reference : RuntimeSettings.required(manifest, "kamelets").split(",")) {
            Path resource = CatalogLoader.contained(sourceRoot, reference.trim());
            if (Files.isSymbolicLink(resource) && Files.isDirectory(resource)) {
                throw new InvalidInput(
                        "Local Kamelet directories must be real directories; replace the directory symlink or list individual files in the manifest.");
            }
            if (Files.isDirectory(resource)) {
                try (var paths = Files.walk(resource)) {
                    for (Path entry : paths.filter(Files::isRegularFile).toList()) {
                        resources.put(sourceRoot.relativize(entry).toString(), entry);
                    }
                }
            } else {
                resources.put(reference.trim(), resource);
            }
        }
        Files.createDirectories(runtime.workDirectory());
        Path root = Files.createTempDirectory(runtime.workDirectory(), "wsr-local-");
        try {
            String digest = snapshot(root, resources);
            var reference =
                    new RuntimeSettings.CatalogReference("local-files", "local", "local", digest, mainReference);
            return CatalogLoader.validateResources(
                    root,
                    CatalogLoader.contained(root, mainReference),
                    CatalogLoader.contained(root, dependencyReference),
                    CatalogLoader.properties(root.resolve(sourceRoot.relativize(manifestPath))),
                    reference);
        } catch (Exception e) {
            CatalogLoader.delete(root);
            throw e;
        }
    }

    private static String snapshot(Path root, TreeMap<String, Path> resources) throws Exception {
        if (resources.size() > 512) {
            throw new IOException("Local resources have too many files");
        }
        long total = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var entry : resources.entrySet()) {
            Path source = entry.getValue();
            if (!Files.isRegularFile(source)) {
                throw new IOException("Local resource is missing: " + entry.getKey());
            }
            total += Files.size(source);
            if (total > 64L * 1024 * 1024) {
                throw new IOException("Local resources exceed snapshot limit");
            }
            Path target = CatalogLoader.contained(root, entry.getKey());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
