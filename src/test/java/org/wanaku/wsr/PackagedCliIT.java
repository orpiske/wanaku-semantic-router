package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks Picocli and version metadata in the distributed JAR and manifest classpath. */
class PackagedCliIT {
    private static final Path JAR = Path.of("target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar");

    @TempDir
    Path directory;

    record Result(int code, String output) {}

    static Result execute(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar", JAR.toString()));
        command.addAll(List.of(args));
        Path log = Files.createTempFile(directory, "cli-", ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI did not exit: " + Files.readString(log));
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void packagedCommandsShowHelpAndManifestVersionWithoutConfiguration() throws Exception {
        String version;
        try (JarFile jar = new JarFile(JAR.toFile())) {
            version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
        assertNotNull(version);
        var root = execute(directory, "--help");
        assertEquals(0, root.code());
        assertTrue(root.output().contains("runtime"));
        assertTrue(root.output().contains("preview"));
        for (String command : new String[] {"runtime", "preview"}) {
            var help = execute(directory, command, "--help");
            assertEquals(0, help.code());
            assertTrue(help.output().contains("[DEPLOYMENT]"));
            assertTrue(help.output().contains("--expert"));
            assertTrue(help.output().contains("--config"));
            var release = execute(directory, command, "--version");
            assertEquals(0, release.code());
            assertTrue(release.output().contains("WSR " + version));
            assertTrue(release.output().contains("Camel " + CatalogLoader.CAMEL_VERSION));
        }
        var release = execute(directory, "--version");
        assertEquals(0, release.code());
        assertTrue(release.output().contains("WSR " + version));
        assertFalse(release.output().contains("development"));
    }

    @Test
    void packagedCommandsRejectMissingSettingsOrUnreadableOptionalConfiguration() throws Exception {
        for (String command : new String[] {"runtime", "preview"}) {
            var missing = execute(directory, command);
            assertEquals(2, missing.code());
            assertTrue(missing.output().contains("Missing setting:"));
            var unreadable = execute(
                    directory, command, directory.resolve("absent.properties").toString());
            assertEquals(2, unreadable.code());
            assertTrue(unreadable.output().contains("Cannot read deployment properties file"));
            assertFalse(unreadable.output().contains("Exception"));
        }
    }
}
