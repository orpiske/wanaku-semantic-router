package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the SDK initialization helper through the distributed JAR's manifest classpath. */
class PackagedInitializationIT {
    @TempDir
    Path directory;

    @Test
    void packagedInitializationRunsSdkHelperFromTheManifestClasspathBeforeReadingConfiguration() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        String content = "wsr.test=pinned-fixture\n";
        try (Git git = Git.init().setDirectory(source.toFile()).call()) {
            Files.writeString(source.resolve("settings.properties"), content);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setSign(false)
                    .setMessage("Deployment fixture")
                    .setAuthor("Fixture", "fixture@localhost")
                    .setCommitter("Fixture", "fixture@localhost")
                    .call();
        }
        for (String command : new String[] {"runtime", "preview"}) {
            for (boolean withFile : new boolean[] {true, false}) {
                Path data = directory.resolve(command + (withFile ? "-file-data" : "-flags-data"));
                List<String> arguments = new ArrayList<>(List.of(command));
                if (withFile) {
                    arguments.add("settings.properties");
                }
                arguments.addAll(List.of(
                        "--init-from", source.toUri().toString(), "--data-dir", data.toString(), "--retries", "0"));
                var result = PackagedCliIT.execute(directory, arguments.toArray(String[]::new));
                assertEquals(2, result.code(), result.output());
                assertTrue(result.output().contains("Missing setting:"), result.output());
                assertEquals(content, Files.readString(data.resolve("cloned-repo/settings.properties")));
                try (var files = Files.list(data)) {
                    assertFalse(
                            files.anyMatch(path -> path.getFileName().toString().startsWith(".wsr-init-")));
                }
            }
            var help = PackagedCliIT.execute(
                    directory, command, "--init-from", "https://user:secret@example.com/repo", "--help");
            assertEquals(0, help.code());
            assertTrue(help.output().contains("--init-from"));
            assertFalse(help.output().contains("secret"));
        }
    }
}
