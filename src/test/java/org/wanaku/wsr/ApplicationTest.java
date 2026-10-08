package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ApplicationTest {
    @TempDir
    Path directory;

    record Result(int code, String output, String error) {}

    static Result execute(String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        int code = Application.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(args);
        return new Result(code, out.toString(), err.toString());
    }

    @Test
    void rootAndSubcommandsOfferHelpWithoutStartingServices() {
        var help = execute("--help");
        assertEquals(0, help.code());
        assertTrue(help.output().contains("runtime"));
        assertTrue(help.output().contains("preview"));
        assertTrue(help.error().isEmpty());
        for (String command : new String[] {"runtime", "preview"}) {
            var subcommand = execute(command, "--help");
            assertEquals(0, subcommand.code());
            assertTrue(subcommand.output().contains("[DEPLOYMENT]"));
            assertTrue(subcommand.output().contains("--expert"));
            assertTrue(subcommand.output().contains("--property"));
            assertTrue(subcommand.output().contains("--config"));
            assertTrue(subcommand.output().contains("--init-from"));
            assertTrue(subcommand.output().contains("--data-dir"));
            assertTrue(subcommand.error().isEmpty());
            var version = execute(command, "--version");
            assertEquals(0, version.code());
            assertTrue(version.output().contains("WSR development"));
            assertTrue(version.output().contains("Camel " + CatalogLoader.CAMEL_VERSION));
        }
        var version = execute("--version");
        assertEquals(0, version.code());
        assertTrue(version.output().contains("WSR development"));
        assertTrue(version.output().contains("Camel " + CatalogLoader.CAMEL_VERSION));
    }

    @Test
    void missingUnknownAndExtraArgumentsReturnUsageErrors() {
        assertEquals(2, execute().code());
        assertEquals(2, execute("unknown").code());
        for (String command : new String[] {"runtime", "preview"}) {
            var missing = execute(command);
            assertEquals(2, missing.code());
            assertTrue(missing.error().contains("Missing setting:"));
            assertEquals(
                    2, execute(command, "first.properties", "second.properties").code());
            assertEquals(2, execute(command, "--unknown").code());
        }
    }

    @Test
    void unreadableAndIncompleteConfigurationReturnSanitizedUsageErrors() throws Exception {
        for (String command : new String[] {"runtime", "preview"}) {
            var missing =
                    execute(command, directory.resolve("absent.properties").toString());
            assertEquals(2, missing.code());
            assertTrue(missing.error().contains("Cannot read deployment properties file"));
            Path invalid = directory.resolve(command + ".properties");
            Files.writeString(invalid, "sensitive.setting=secret-fixture-value\n");
            var incomplete = execute(command, invalid.toString());
            assertEquals(2, incomplete.code());
            assertTrue(incomplete.error().contains("Missing setting:"));
            assertFalse(incomplete.error().contains("secret-fixture-value"));
            assertFalse(incomplete.error().contains("Exception"));
        }
    }

    @Test
    void invalidPortConfigurationDoesNotEchoItsValue() throws Exception {
        Path file = directory.resolve("preview.properties");
        Files.writeString(
                file,
                "wsr.experts=test\n"
                        + "wsr.expert.test.class=example.TestAdapter\n"
                        + "wsr.preview.port=secret-fixture-value\n");
        var invalid = execute("preview", file.toString());
        assertEquals(2, invalid.code());
        assertTrue(invalid.error().contains("Invalid deployment configuration"));
        assertFalse(invalid.error().contains("secret-fixture-value"));
    }

    @Test
    @Timeout(10)
    void previewStartupFailureReturnsSoftwareErrorWithoutConfigurationValues() throws Exception {
        try (var occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            Path file = directory.resolve("preview.properties");
            Files.writeString(
                    file,
                    "wsr.experts=test\nwsr.expert.test.class=example.TestAdapter\nwsr.preview.port="
                            + occupied.getLocalPort()
                            + "\nwsr.preview.bind=127.0.0.1\nsensitive.setting=secret-fixture-value\n");
            var failure = execute("preview", file.toString());
            assertEquals(1, failure.code());
            assertTrue(failure.error().contains("WSR command failed:"));
            assertTrue(failure.error().contains("BindException"));
            assertFalse(failure.error().contains("secret-fixture-value"));
            assertFalse(failure.error().contains("\tat "));
        }
    }
}
