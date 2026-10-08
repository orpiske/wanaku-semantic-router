package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitializationCliTest {
    @TempDir
    Path directory;

    @Test
    void initializationHelpBypassesInvalidSourcesAndLimitsAndCreatesNoFiles() {
        for (String command : new String[] {"runtime", "preview"}) {
            Path data = directory.resolve(command + "-help-data");
            var help = ApplicationTest.execute(
                    command,
                    "--init-from",
                    "https://user:secret-fixture-value@example.com/repo",
                    "--data-dir",
                    data.toString(),
                    "--retries",
                    "999",
                    "--help");
            assertEquals(0, help.code());
            assertTrue(help.error().isEmpty());
            assertFalse(help.output().contains("secret-fixture-value"));
            assertFalse(Files.exists(data));
            var invalidSource = ApplicationTest.execute(
                    command,
                    "--init-from",
                    "https://user:secret-fixture-value@example.com/repo",
                    "--data-dir",
                    data.toString());
            assertEquals(2, invalidSource.code());
            assertTrue(invalidSource.error().contains("Invalid initialization source"));
            assertFalse(invalidSource.error().contains("secret-fixture-value"));
            assertFalse(Files.exists(data));
            for (String[] invalid : new String[][] {
                {"--retries", "-1"}, {"--retries", "13"}, {"--wait-seconds", "0"},
                {"--wait-seconds", "31"}, {"--init-timeout-seconds", "0"}, {"--init-timeout-seconds", "601"}
            }) {
                var limits = ApplicationTest.execute(command, invalid[0], invalid[1]);
                assertEquals(2, limits.code());
                assertTrue(limits.error().contains("Invalid initialization limits"));
            }
        }
    }
}
