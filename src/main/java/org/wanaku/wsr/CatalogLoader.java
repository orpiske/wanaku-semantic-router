package org.wanaku.wsr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipInputStream;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.common.config.DefaultServiceConfig;
import ai.wanaku.capabilities.sdk.common.serializer.JacksonSerializer;
import ai.wanaku.capabilities.sdk.runtime.camel.downloader.ServiceCatalogExtractor;
import ai.wanaku.capabilities.sdk.services.ServicesHttpClient;

/** Loads one pinned Barn DataStore archive into a private, bounded extraction directory. */
public final class CatalogLoader {
    public static final String CAMEL_VERSION = "4.23.0-SNAPSHOT";

    public record Catalog(Path root, Path main, Path kamelets, Path dependencies, Properties manifest) {}

    private static final long MAX_UNPACKED = 64L * 1024 * 1024;

    /**
     * Downloads the selected catalog and validates its pinned contents.
     *
     * @param settings the Barn location and fixed catalog selection
     * @param runtime the local work directory and HTTP deadline
     * @return the private directory and validated catalog resources
     * @throws Exception if downloading, extracting, or validating the catalog fails
     */
    public Catalog fetch(RuntimeSettings.CatalogSettings settings, RuntimeSettings.RuntimeOptions runtime)
            throws Exception {
        var config = DefaultServiceConfig.Builder.newBuilder()
                .baseUrl(settings.barn().toString())
                .serializer(new JacksonSerializer())
                .build();
        var client = new ServicesHttpClient(config, Http.sdkClient(), builder -> {
            builder.timeout(runtime.httpTimeout());
            if (settings.token() != null) {
                builder.setHeader("Authorization", "Bearer " + settings.token());
            }
            return builder;
        });
        DataStore store;
        try {
            store = client.getServiceCatalog(settings.reference().name()).data();
        } catch (WanakuException failure) {
            if (failure.getCause() instanceof HttpTimeoutException timeout) {
                throw timeout;
            }
            if (failure.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            // SDK errors can contain remote response bodies. Keep them out of runtime diagnostics.
            throw new IOException("Barn catalog download failed");
        }
        return extract(settings.reference(), runtime, decodeArchive(store));
    }

    static byte[] decodeArchive(DataStore store) throws IOException {
        if (store == null || store.getData() == null) {
            throw new IOException("Barn did not return a DataStore archive");
        }
        try {
            return Base64.getDecoder().decode(store.getData());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Barn archive encoding", e);
        }
    }

    /**
     * Extracts an archive with the SDK after checking its digest and extraction limits.
     *
     * @param selection the catalog name, service, revision, and expected digest
     * @param runtime the local work directory
     * @param archive the downloaded ZIP bytes
     * @return the private directory and validated catalog resources
     * @throws Exception if the archive does not satisfy the catalog contract
     */
    public Catalog extract(
            RuntimeSettings.CatalogReference selection, RuntimeSettings.RuntimeOptions runtime, byte[] archive)
            throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        if (!MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.UTF_8), selection.digest().getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Catalog digest mismatch");
        }
        Files.createDirectories(runtime.workDirectory());
        Path root = Files.createTempDirectory(runtime.workDirectory(), "wsr-catalog-");
        try {
            validateArchive(root, archive);
            ServiceCatalogExtractor.extract(Base64.getEncoder().encodeToString(archive), selection.service(), root);
            return validate(root, selection);
        } catch (Exception e) {
            delete(root);
            throw e;
        }
    }

    /** Checks the archive limits before the SDK extractor writes any files. */
    static void validateArchive(Path root, byte[] archive) throws IOException {
        Set<Path> seen = new HashSet<>();
        long size = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 512) {
                    throw new IOException("Archive has too many entries");
                }
                String name = entry.getName();
                if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")) {
                    throw new IOException("Unsafe archive entry");
                }
                Path relative = Path.of(name).normalize();
                Path path = root.resolve(relative).normalize();
                if (!path.startsWith(root) || relative.startsWith("..") || !seen.add(path)) {
                    throw new IOException("Unsafe or duplicate archive entry");
                }
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_UNPACKED) {
                        throw new IOException("Archive exceeds extraction limit");
                    }
                }
            }
        }
    }

    static Catalog validate(Path root, RuntimeSettings.CatalogReference selection) throws IOException {
        Properties index = properties(root.resolve("index.properties"));
        require(index, "catalog.name", selection.name());
        String mainReference = index.getProperty("catalog.routes." + selection.service());
        if (mainReference == null) {
            throw new IOException("Selected catalog service is absent");
        }
        Path main = contained(root, selection.main() == null ? mainReference : selection.main());
        if (!mainReference.equals(root.relativize(main).toString())) {
            throw new IOException("Main route differs from selected catalog service");
        }
        Path manifestPath = main.getParent().resolve("semantic-router.properties");
        Properties manifest = properties(manifestPath);
        require(manifest, "contract.version", "1");
        require(manifest, "catalog.revision", selection.revision());
        require(manifest, "camel.version", CAMEL_VERSION);
        require(manifest, "input.profile", "message-to-string/v1");
        require(manifest, "main", mainReference);
        String dependencyReference = RuntimeSettings.required(manifest, "dependencies");
        if (!dependencyReference.equals(index.getProperty("catalog.dependencies." + selection.service()))) {
            throw new IOException("Dependency reference differs from index");
        }
        Path dependencies = contained(root, dependencyReference);
        Path kamelets = validateKamelets(root, manifest);
        String configurationReference = RuntimeSettings.required(manifest, "configuration");
        if (!configurationReference.equals(index.getProperty("catalog.properties." + selection.service()))) {
            throw new IOException("Configuration reference differs from index");
        }
        properties(contained(root, configurationReference));
        if (!Files.isRegularFile(main) || !Files.isRegularFile(dependencies) || !Files.isDirectory(kamelets)) {
            throw new IOException("Catalog resource is missing");
        }
        RuntimeSettings.identifier(RuntimeSettings.required(manifest, "expert.bean"));
        RuntimeSettings.identifier(RuntimeSettings.required(manifest, "tool.name"));
        return new Catalog(root, main, kamelets, dependencies, manifest);
    }

    private static Path validateKamelets(Path root, Properties manifest) throws IOException {
        Path kamelets = null;
        for (String reference : RuntimeSettings.required(manifest, "kamelets").split(",")) {
            Path resource = contained(root, reference.trim());
            if (!Files.exists(resource)) {
                throw new IOException("Auxiliary Kamelet is missing");
            }
            Path location = Files.isDirectory(resource) ? resource : resource.getParent();
            if (kamelets != null && !kamelets.equals(location)) {
                throw new IOException("Kamelets must share one resource directory");
            }
            kamelets = location;
        }
        return kamelets;
    }

    static Path contained(Path root, String value) throws IOException {
        Path relative = Path.of(value);
        Path path = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(root) || value.contains("\\")) {
            throw new IOException("Unsafe catalog resource path");
        }
        return path;
    }

    static Properties properties(Path path) throws IOException {
        Properties p = new Properties();
        try (var stream = Files.newInputStream(path)) {
            p.load(stream);
        }
        return p;
    }

    static void require(Properties p, String key, String expected) throws IOException {
        if (!expected.equals(p.getProperty(key))) {
            throw new IOException("Incompatible artifact setting: " + key);
        }
    }

    static void delete(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
