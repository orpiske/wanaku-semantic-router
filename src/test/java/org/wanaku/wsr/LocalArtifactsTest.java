package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import picocli.CommandLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactsTest {
    @TempDir
    Path directory;

    private Properties sources() throws Exception {
        RuntimeTest.writeLocalSources(directory.resolve("source"));
        Properties deployment = new Properties();
        deployment.setProperty(
                "wsr.routes-ref",
                directory.resolve("source/support/support.camel.yaml").toUri().toString());
        return deployment;
    }

    private CatalogLoader.Catalog load(Properties deployment) throws Exception {
        return LocalArtifacts.load(
                deployment,
                new RuntimeSettings.RuntimeOptions(
                        8090, "127.0.0.1", directory.resolve("work"), Duration.ofSeconds(30)));
    }

    @Test
    void snapshotsResourcesWithoutIndexAndUsesExternalDependencyOverride() throws Exception {
        Properties deployment = sources();
        Path external = directory.resolve("external.dependencies.txt");
        Files.writeString(external, "# external override\n");
        deployment.setProperty("wsr.dependencies", external.toString());
        Files.delete(directory.resolve("source/support/support.dependencies.txt"));
        var first = load(deployment);
        var second = load(deployment);
        assertEquals("# external override\n", Files.readString(first.dependencies()));
        assertEquals("local", first.reference().revision());
        assertEquals(first.reference().digest(), second.reference().digest());
        assertTrue(Files.exists(first.kamelets().resolve("wsr-billing-action.kamelet.yaml")));
        Files.writeString(external, "# changed\n");
        assertEquals("# external override\n", Files.readString(first.dependencies()));
        var changed = load(deployment);
        assertFalse(first.reference().digest().equals(changed.reference().digest()));
        CatalogLoader.delete(first.root());
        CatalogLoader.delete(second.root());
        CatalogLoader.delete(changed.root());
        assertTrue(Files.exists(external));
        assertTrue(Files.exists(LocalArtifacts.path(deployment.getProperty("wsr.routes-ref"))));
    }

    @Test
    void validatesMissingAndIncompatibleLocalResources() throws Exception {
        Properties deployment = sources();
        Path manifest = directory.resolve("source/support/semantic-router.properties");
        String contents = Files.readString(manifest);
        Files.writeString(manifest, contents.replace("contract.version=1", "contract.version=2"));
        assertTrue(assertThrows(LocalArtifacts.InvalidInput.class, () -> load(deployment))
                .getMessage()
                .contains("contract.version"));
        Files.writeString(manifest, contents.replace("main=support/support.camel.yaml", "main=../support.camel.yaml"));
        assertThrows(LocalArtifacts.InvalidInput.class, () -> load(deployment));
        Files.writeString(manifest, contents);
        Files.delete(directory.resolve("source/support/kamelets/wsr-billing-action.kamelet.yaml"));
        Files.delete(directory.resolve("source/support/kamelets/wsr-technical-action.kamelet.yaml"));
        Files.delete(directory.resolve("source/support/kamelets"));
        assertThrows(LocalArtifacts.InvalidInput.class, () -> load(deployment));
        Files.delete(manifest);
        assertTrue(assertThrows(LocalArtifacts.InvalidInput.class, () -> load(deployment))
                .getMessage()
                .contains("adjacent"));
    }

    @Test
    void reportsUnsupportedKameletDirectorySymlinks() throws Exception {
        Properties deployment = sources();
        Path kamelets = directory.resolve("source/support/kamelets");
        Path real = directory.resolve("real-kamelets");
        Files.move(kamelets, real);
        Files.createSymbolicLink(kamelets, real);
        assertTrue(assertThrows(LocalArtifacts.InvalidInput.class, () -> load(deployment))
                .getMessage()
                .contains("directory symlink"));
    }

    @Test
    void cliSelectsLocalModeWithoutBarnAndRejectsAmbiguousSelections() throws Exception {
        var command = new RuntimeCommand();
        new CommandLine(command)
                .parseArgs("--routes-ref", "/tmp/main.camel.yaml", "--dependencies", "file:///tmp/dependencies.txt");
        var deployment = command.deployment(Map.of());
        assertEquals("/tmp/main.camel.yaml", deployment.getProperty("wsr.routes-ref"));
        assertFalse(deployment.containsKey("wsr.barn.url"));
        assertFalse(deployment.containsKey("wsr.catalog.service"));
        deployment.setProperty("wsr.semantic-route", "published");
        assertThrows(IllegalArgumentException.class, () -> LocalArtifacts.validateSelection(deployment));
        deployment.remove("wsr.semantic-route");
        deployment.setProperty("wsr.catalog.sha256", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> LocalArtifacts.validateSelection(deployment));
        assertThrows(IllegalArgumentException.class, () -> LocalArtifacts.path("https://example.com/route"));
        Properties dependenciesOnly = new Properties();
        dependenciesOnly.setProperty("wsr.dependencies", "/tmp/dependencies.txt");
        assertThrows(LocalArtifacts.InvalidInput.class, () -> RuntimeSettings.from(dependenciesOnly));
        var invalidCommand = new RuntimeCommand();
        new CommandLine(invalidCommand)
                .parseArgs("--routes-ref", "/tmp/main.camel.yaml", "--semantic-route", "published");
        assertThrows(LocalArtifacts.InvalidInput.class, () -> invalidCommand.deployment(Map.of()));
        var dependenciesCommand = new RuntimeCommand();
        new CommandLine(dependenciesCommand)
                .parseArgs("--dependencies", "/tmp/dependencies.txt", "--semantic-route", "published");
        assertThrows(LocalArtifacts.InvalidInput.class, () -> dependenciesCommand.deployment(Map.of()));
    }
}
