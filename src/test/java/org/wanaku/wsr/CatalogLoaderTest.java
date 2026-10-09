package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import ai.wanaku.capabilities.sdk.api.types.DataStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogLoaderTest {
    @TempDir
    Path directory;

    @Test
    void decodesSdkDataStoreArchive() throws Exception {
        byte[] archive = RuntimeTest.archive(RuntimeTest.reference());
        var store = new DataStore(
                "catalog-id", "semantic-support-spike", Base64.getEncoder().encodeToString(archive));
        assertArrayEquals(archive, CatalogLoader.decodeArchive(store));
    }

    @Test
    void rejectsMissingAndMalformedBase64ArchiveData() {
        assertThrows(IOException.class, () -> CatalogLoader.decodeArchive(null));
        assertThrows(IOException.class, () -> CatalogLoader.decodeArchive(new DataStore()));
        assertThrows(IOException.class, () -> CatalogLoader.decodeArchive(new DataStore("id", "catalog", "%%%")));
    }

    @Test
    void acceptsPublicationBuildMetadataWithoutPinningRuntimeBinaries() throws Exception {
        for (String build : List.of("20261006.103638", "20261007.120000", "")) {
            var contents = RuntimeTest.reference();
            String manifest = new String(contents.get("support/semantic-router.properties"), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.startsWith("camel.build="))
                    .collect(Collectors.joining("\n"));
            if (!build.isEmpty()) {
                manifest += "\ncamel.build=" + build;
            }
            contents.put("support/semantic-router.properties", manifest.getBytes(StandardCharsets.UTF_8));
            var catalog = extract(RuntimeTest.archive(contents));
            try {
                assertEquals(build.isEmpty() ? null : build, catalog.manifest().getProperty("camel.build"));
                assertEquals(CatalogLoader.CAMEL_VERSION, catalog.manifest().getProperty("camel.version"));
            } finally {
                CatalogLoader.delete(catalog.root());
            }
        }
    }

    @Test
    void validatesOptionalGuardManifest() throws Exception {
        for (String guard : List.of(
                "guard.expert.bean=injectionGuard\nguard.operation=injection\nguard.rejectWhen=true",
                "guard.expert.bean=injectionGuard\nguard.operation=injection\nguard.rejectWhen=false",
                "guard.operation=injection\nguard.rejectWhen=true",
                "guard.expert.bean=injectionGuard\nguard.operation=injection\nguard.rejectWhen=invalid")) {
            var contents = RuntimeTest.reference();
            String manifest = new String(contents.get("support/semantic-router.properties"), StandardCharsets.UTF_8);
            contents.put(
                    "support/semantic-router.properties", (manifest + "\n" + guard).getBytes(StandardCharsets.UTF_8));
            byte[] archive = RuntimeTest.archive(contents);
            if (!guard.startsWith("guard.expert.bean=") || guard.endsWith("invalid")) {
                assertThrows(IOException.class, () -> extract(archive));
            } else {
                var catalog = extract(archive);
                try {
                    assertEquals("injectionGuard", catalog.manifest().getProperty("guard.expert.bean"));
                } finally {
                    CatalogLoader.delete(catalog.root());
                }
            }
        }
    }

    @Test
    void rejectsIncompatibleCamelVersion() throws Exception {
        var contents = RuntimeTest.reference();
        String manifest = new String(contents.get("support/semantic-router.properties"), StandardCharsets.UTF_8)
                .replace("camel.version=4.23.0-SNAPSHOT", "camel.version=4.22.0");
        contents.put("support/semantic-router.properties", manifest.getBytes(StandardCharsets.UTF_8));
        byte[] archive = RuntimeTest.archive(contents);
        assertThrows(IOException.class, () -> extract(archive));
    }

    @Test
    void rejectsUnsafePathsBeforeSdkExtractionWritesFiles() throws Exception {
        for (String name : List.of("../outside", "/outside", "C:/outside", "folder\\outside", "folder/../../outside")) {
            var contents = RuntimeTest.reference();
            contents.put(name, new byte[] {1});
            byte[] archive = RuntimeTest.archive(contents);
            assertThrows(IOException.class, () -> extract(archive), name);
            assertWorkDirectoryEmpty();
        }
    }

    @Test
    void rejectsDuplicateNormalizedPathsBeforeSdkExtraction() throws Exception {
        var contents = RuntimeTest.reference();
        contents.put("support/../index.properties", new byte[] {1});
        assertThrows(IOException.class, () -> extract(RuntimeTest.archive(contents)));
        assertWorkDirectoryEmpty();
    }

    @Test
    void rejectsArchivesWithTooManyEntriesBeforeSdkExtraction() throws Exception {
        Map<String, byte[]> contents = new TreeMap<>();
        for (int i = 0; i < 513; i++) {
            contents.put("file-" + i, new byte[0]);
        }
        var error = assertThrows(IOException.class, () -> extract(RuntimeTest.archive(contents)));
        assertTrue(error.getMessage().contains("too many entries"));
        assertWorkDirectoryEmpty();
    }

    @Test
    void rejectsExpandedArchiveLimitBeforeSdkExtractionWritesFiles() throws Exception {
        var contents = RuntimeTest.reference();
        contents.put("support/large", new byte[64 * 1024 * 1024 + 1]);
        var error = assertThrows(IOException.class, () -> extract(RuntimeTest.archive(contents)));
        assertTrue(error.getMessage().contains("extraction limit"));
        assertWorkDirectoryEmpty();
    }

    @Test
    void rejectsWrongDigestBeforeCreatingExtractionDirectory() throws Exception {
        var selection =
                new RuntimeSettings.CatalogReference("semantic-support-spike", "support", "1", "0".repeat(64), null);
        Path work = directory.resolve("work");
        var runtime = new RuntimeSettings.RuntimeOptions(8090, "127.0.0.1", work, Duration.ofSeconds(30));
        var error = assertThrows(IOException.class, () -> new CatalogLoader()
                .extract(selection, runtime, RuntimeTest.archive(RuntimeTest.reference())));
        assertEquals("Catalog digest mismatch", error.getMessage());
        assertFalse(Files.exists(work));
    }

    private void assertWorkDirectoryEmpty() throws IOException {
        try (var children = Files.list(directory)) {
            assertEquals(0, children.count(), "Failed extraction must remove its private directory");
        }
    }

    private CatalogLoader.Catalog extract(byte[] archive) throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        var selection = new RuntimeSettings.CatalogReference("semantic-support-spike", "support", "1", digest, null);
        var runtime = new RuntimeSettings.RuntimeOptions(8090, "127.0.0.1", directory, Duration.ofSeconds(30));
        return new CatalogLoader().extract(selection, runtime, archive);
    }
}
