package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises Picocli binding and deployment merging without opening service listeners. */
class DeploymentOptionsTest {
    @TempDir
    Path directory;

    static Properties deployment(String command, String... args) throws Exception {
        var cli = Application.commandLine();
        List<String> arguments = new ArrayList<>();
        arguments.add(command);
        arguments.addAll(List.of(args));
        cli.parseArgs(arguments.toArray(String[]::new));
        Object target = cli.getSubcommands().get(command).getCommand();
        return target instanceof RuntimeCommand runtime ? runtime.deployment() : ((PreviewCommand) target).deployment();
    }

    static List<String> runtimeArguments() {
        return new ArrayList<>(List.of(
                "runtime",
                "--barn-url",
                "http://127.0.0.1:8080",
                "--registration-url",
                "http://127.0.0.1:8081",
                "--service-catalog",
                "catalog",
                "--catalog-revision",
                "1",
                "--catalog-sha256",
                "a".repeat(64),
                "--name",
                "support",
                "--mcp-address",
                "http://127.0.0.1:8090/mcp",
                "--expert",
                "support=example.SupportAdapter"));
    }

    @Test
    void runtimeOptionsAndCicAliasesMapToDeploymentSettingsWithoutAFile() throws Exception {
        var settings = deployment(
                "runtime",
                "--barn-url",
                "http://127.0.0.1:8100",
                "--wanaku-url",
                "http://127.0.0.1:8101",
                "--catalog-name",
                "support-catalog",
                "--catalog-service",
                "support",
                "--catalog-revision",
                "7",
                "--catalog-sha256",
                "a".repeat(64),
                "--catalog-main",
                "support/main.camel.yaml",
                "--forward-name",
                "support-forward",
                "--namespace",
                "team",
                "--mcp-address",
                "http://127.0.0.1:8102/mcp",
                "--port",
                "8102",
                "--bind",
                "0.0.0.0",
                "--status-port",
                "8103",
                "--status-bind",
                "0.0.0.0",
                "--http-timeout-ms",
                "1500",
                "--work-directory",
                directory.toString(),
                "--barn-token-env",
                "BARN_TOKEN",
                "--wanaku-token-env",
                "WANAKU_TOKEN",
                "--expert",
                "support=example.SupportAdapter",
                "--expert",
                "billing=example.BillingAdapter",
                "-p",
                "camel.component.typesafe-ai.model=fixture");
        assertEquals("http://127.0.0.1:8100", settings.getProperty("wsr.barn.url"));
        assertEquals("http://127.0.0.1:8101", settings.getProperty("wsr.wanaku.url"));
        assertEquals("support-catalog", settings.getProperty("wsr.catalog.name"));
        assertEquals("support", settings.getProperty("wsr.catalog.service"));
        assertEquals("7", settings.getProperty("wsr.catalog.revision"));
        assertEquals("a".repeat(64), settings.getProperty("wsr.catalog.sha256"));
        assertEquals("support/main.camel.yaml", settings.getProperty("wsr.catalog.main"));
        assertEquals("support-forward", settings.getProperty("wsr.forward.name"));
        assertEquals("team", settings.getProperty("wsr.forward.namespace"));
        assertEquals("http://127.0.0.1:8102/mcp", settings.getProperty("wsr.mcp.address"));
        assertEquals("8102", settings.getProperty("wsr.port"));
        assertEquals("0.0.0.0", settings.getProperty("wsr.bind"));
        assertEquals("8103", settings.getProperty("wsr.status.port"));
        assertEquals("0.0.0.0", settings.getProperty("wsr.status.bind"));
        assertEquals("1500", settings.getProperty("wsr.http.timeout-ms"));
        assertEquals(directory.toString(), settings.getProperty("wsr.work-directory"));
        assertEquals("BARN_TOKEN", settings.getProperty("wsr.barn.token-env"));
        assertEquals("WANAKU_TOKEN", settings.getProperty("wsr.wanaku.token-env"));
        assertEquals("support,billing", settings.getProperty("wsr.experts"));
        assertEquals("example.SupportAdapter", settings.getProperty("wsr.expert.support.class"));
        assertEquals("example.BillingAdapter", settings.getProperty("wsr.expert.billing.class"));
        assertEquals("fixture", settings.getProperty("camel.component.typesafe-ai.model"));
    }

    @Test
    void runtimeDefaultsSelectServiceAndDefaultNamespaceWithoutOverwritingProperties() throws Exception {
        var defaults = deployment("runtime");
        assertEquals("service", defaults.getProperty("wsr.catalog.service"));
        assertEquals("default", defaults.getProperty("wsr.forward.namespace"));
        var configured = deployment("runtime", "-p", "wsr.catalog.service=support", "-p", "wsr.forward.namespace=team");
        assertEquals("support", configured.getProperty("wsr.catalog.service"));
        assertEquals("team", configured.getProperty("wsr.forward.namespace"));
    }

    @Test
    void previewOptionsMapToDeploymentSettingsAndPreservePropertyEqualsSigns() throws Exception {
        var settings = deployment(
                "preview",
                "--preview-port",
                "8200",
                "--bind",
                "0.0.0.0",
                "--preview-timeout-ms",
                "5000",
                "--max-concurrent",
                "2",
                "--preview-token-env",
                "PREVIEW_TOKEN",
                "--expert",
                "first=example.FirstAdapter",
                "--expert",
                "second=example.SecondAdapter",
                "--property",
                "provider.api-key=value=with=equals");
        assertEquals("8200", settings.getProperty("wsr.preview.port"));
        assertEquals("0.0.0.0", settings.getProperty("wsr.preview.bind"));
        assertEquals("5000", settings.getProperty("wsr.preview.timeout-ms"));
        assertEquals("2", settings.getProperty("wsr.preview.max-concurrent"));
        assertEquals("PREVIEW_TOKEN", settings.getProperty("wsr.preview.token-env"));
        assertEquals("first,second", settings.getProperty("wsr.experts"));
        assertEquals("value=with=equals", settings.getProperty("provider.api-key"));
        assertEquals("8201", deployment("preview", "--port", "8201").getProperty("wsr.preview.port"));
    }

    @Test
    void namedFlagsOverrideGenericPropertiesAndFilesRegardlessOfArgumentOrder() throws Exception {
        Path file = directory.resolve("deployment.properties");
        Files.writeString(
                file,
                "wsr.preview.port=8100\nwsr.experts=file\nwsr.expert.file.class=example.File\nprovider.model=file\n");
        for (boolean namedFirst : new boolean[] {true, false}) {
            List<String> named = List.of("--port", "8300", "--expert", "explicit=example.Explicit");
            List<String> generic = List.of(
                    "--property", "wsr.preview.port=8200",
                    "--property", "wsr.experts=generic",
                    "--property", "wsr.expert.explicit.class=example.Generic",
                    "--property", "provider.model=generic");
            List<String> args = new ArrayList<>(List.of("--config", file.toString()));
            args.addAll(namedFirst ? named : generic);
            args.addAll(namedFirst ? generic : named);
            var settings = deployment("preview", args.toArray(String[]::new));
            assertEquals("8300", settings.getProperty("wsr.preview.port"));
            assertEquals("explicit", settings.getProperty("wsr.experts"));
            assertEquals("example.Explicit", settings.getProperty("wsr.expert.explicit.class"));
            assertEquals("generic", settings.getProperty("provider.model"));
        }
        var legacy = deployment("preview", file.toString());
        assertEquals("8100", legacy.getProperty("wsr.preview.port"));
        assertEquals("file", legacy.getProperty("wsr.experts"));
    }

    @Test
    void dataDirectoryChangesRuntimeWorkDirectoryOnlyWhenExplicitAndWorkDirectoryWins() throws Exception {
        Path file = directory.resolve("runtime.properties");
        Files.writeString(file, "wsr.work-directory=" + directory.resolve("file-work") + "\n");
        Path data = directory.resolve("data"), explicit = directory.resolve("explicit");
        assertEquals(
                directory.resolve("file-work").toString(),
                deployment("runtime", file.toString()).getProperty("wsr.work-directory"));
        assertEquals(
                data.toString(),
                deployment("runtime", file.toString(), "--data-dir", data.toString())
                        .getProperty("wsr.work-directory"));
        assertEquals(
                explicit.toString(),
                deployment("runtime", "--work-directory", explicit.toString(), "--data-dir", data.toString())
                        .getProperty("wsr.work-directory"));
    }

    @Test
    void malformedBindingsAndValuesProduceUsageErrorsWithoutEchoingCredentials() {
        for (String[] invalid : new String[][] {
            {"--expert", "secret-fixture-value"},
            {"--expert", "bad/name=secret-fixture-value"},
            {"--expert", "valid=secret-fixture-value/Bad"},
            {"--property", "secret-fixture-value"},
            {"--property", "=secret-fixture-value"},
            {"--port", "secret-fixture-value"},
            {"--port", "0"},
            {"--port", "65536"},
            {"--preview-timeout-ms", "secret-fixture-value"},
            {"--max-concurrent", "secret-fixture-value"}
        }) {
            var result =
                    ApplicationTest.execute("preview", "--expert", "support=example.Support", invalid[0], invalid[1]);
            assertEquals(2, result.code());
            assertFalse(result.error().contains("secret-fixture-value"), result.error());
            assertFalse(result.error().contains("Exception"), result.error());
        }
        var args = runtimeArguments();
        args.set(args.indexOf("--barn-url") + 1, "http://user:secret-fixture-value@127.0.0.1:8080");
        var invalid = ApplicationTest.execute(args.toArray(String[]::new));
        assertEquals(2, invalid.code());
        assertFalse(invalid.error().contains("secret-fixture-value"), invalid.error());
    }

    @Test
    void runtimeStillRequiresPinsAndExpertWhenUsingFlags() {
        for (String option : List.of(
                "--catalog-revision", "--catalog-sha256", "--expert", "--barn-url", "--registration-url", "--name")) {
            var args = runtimeArguments();
            int index = args.indexOf(option);
            args.remove(index + 1);
            args.remove(index);
            var missing = ApplicationTest.execute(args.toArray(String[]::new));
            assertEquals(2, missing.code(), option);
            assertTrue(missing.error().contains("Missing setting:"), missing.error());
        }
    }

    @Test
    void helpAndVersionIgnoreConfigurationAndInitializationWithoutCreatingFiles() {
        for (String command : List.of("runtime", "preview")) {
            for (String flag : List.of("--help", "--version")) {
                Path data = directory.resolve(command + flag);
                var result = ApplicationTest.execute(
                        command,
                        "--config",
                        directory.resolve("absent.properties").toString(),
                        "--init-from",
                        "https://user:secret-fixture-value@example.org/repo",
                        "--data-dir",
                        data.toString(),
                        flag);
                assertEquals(0, result.code());
                assertTrue(result.error().isEmpty());
                assertFalse(result.output().contains("secret-fixture-value"));
                assertFalse(Files.exists(data));
            }
        }
    }

    @Test
    void environmentOverridesFilesAndGenericAndNamedOptionsOverrideEnvironmentInAnyOrder() throws Exception {
        Path file = directory.resolve("env-runtime.properties");
        Files.writeString(file, "wsr.port=8100\nwsr.barn.url=http://file.example:8180\n");
        Map<String, String> environment = Map.of(
                "WSR_MCP_PORT", "8101",
                "WSR_BARN_URL", "http://env.example:8180",
                "WSR_REGISTRATION_URL", "http://env.example:8080",
                "WSR_BARN_TOKEN_ENV", "BARN_TOKEN",
                "WSR_WANAKU_TOKEN_ENV", "WANAKU_TOKEN");
        for (int level = 0; level < 3; level++) {
            for (boolean namedFirst : new boolean[] {true, false}) {
                List<String> arguments = new ArrayList<>(List.of("runtime", "--config", file.toString()));
                List<String> generic = level >= 1
                        ? List.of("-p", "wsr.port=8102", "-p", "wsr.barn.url=http://property.example:8180")
                        : List.of();
                List<String> named = level >= 2
                        ? List.of("--mcp-port", "8103", "--barn-url", "http://named.example:8180")
                        : List.of();
                arguments.addAll(namedFirst ? named : generic);
                arguments.addAll(namedFirst ? generic : named);
                var cli = Application.commandLine();
                cli.parseArgs(arguments.toArray(String[]::new));
                var settings =
                        ((RuntimeCommand) cli.getSubcommands().get("runtime").getCommand()).deployment(environment);
                assertEquals(Integer.toString(8101 + level), settings.getProperty("wsr.port"));
                assertEquals(
                        "http://" + List.of("env", "property", "named").get(level) + ".example:8180",
                        settings.getProperty("wsr.barn.url"));
                assertEquals("http://env.example:8080", settings.getProperty("wsr.wanaku.url"));
                assertEquals("BARN_TOKEN", settings.getProperty("wsr.barn.token-env"));
                assertEquals("WANAKU_TOKEN", settings.getProperty("wsr.wanaku.token-env"));
            }
        }
    }

    @Test
    void k8sEnvironmentControlsWorkDirectoryListenersAndImmutableCatalogSelection() throws Exception {
        Map<String, String> environment = Map.ofEntries(
                Map.entry("WSR_DATA_DIR", directory.resolve("env-data").toString()),
                Map.entry("WSR_WORK_DIRECTORY", directory.resolve("env-work").toString()),
                Map.entry("WSR_BIND", "0.0.0.0"),
                Map.entry("WSR_MCP_PORT", "8200"),
                Map.entry("WSR_MCP_ADDRESS", "http://wsr.example:8200/mcp"),
                Map.entry("WSR_STATUS_PORT", "8201"),
                Map.entry("WSR_STATUS_BIND", "0.0.0.0"),
                Map.entry("WSR_NAME", "env-forward"),
                Map.entry("WSR_NAMESPACE", "team"),
                Map.entry("WSR_HTTP_TIMEOUT_MS", "5000"),
                Map.entry("WSR_CATALOG_NAME", "catalog"),
                Map.entry("WSR_CATALOG_SERVICE", "service"),
                Map.entry("WSR_CATALOG_REVISION", "7"),
                Map.entry("WSR_CATALOG_SHA256", "a".repeat(64)),
                Map.entry("WSR_CATALOG_MAIN", "service/main.camel.yaml"));
        var cli = Application.commandLine();
        cli.parseArgs("runtime");
        var settings = ((RuntimeCommand) cli.getSubcommands().get("runtime").getCommand()).deployment(environment);
        assertEquals(directory.resolve("env-work").toString(), settings.getProperty("wsr.work-directory"));
        assertEquals("0.0.0.0", settings.getProperty("wsr.bind"));
        assertEquals("8200", settings.getProperty("wsr.port"));
        assertEquals("http://wsr.example:8200/mcp", settings.getProperty("wsr.mcp.address"));
        assertEquals("8201", settings.getProperty("wsr.status.port"));
        assertEquals("0.0.0.0", settings.getProperty("wsr.status.bind"));
        assertEquals("env-forward", settings.getProperty("wsr.forward.name"));
        assertEquals("team", settings.getProperty("wsr.forward.namespace"));
        assertEquals("5000", settings.getProperty("wsr.http.timeout-ms"));
        assertEquals("catalog", settings.getProperty("wsr.catalog.name"));
        assertEquals("service", settings.getProperty("wsr.catalog.service"));
        assertEquals("7", settings.getProperty("wsr.catalog.revision"));
        assertEquals("a".repeat(64), settings.getProperty("wsr.catalog.sha256"));
        assertEquals("service/main.camel.yaml", settings.getProperty("wsr.catalog.main"));
    }

    @Test
    void ambiguousConfigurationFilesReturnAUsageError() throws Exception {
        Path first = directory.resolve("first.properties"), second = directory.resolve("second.properties");
        Files.writeString(first, "wsr.experts=first\nwsr.expert.first.class=example.First\n");
        Files.writeString(second, "wsr.experts=second\nwsr.expert.second.class=example.Second\n");
        var result = ApplicationTest.execute("preview", first.toString(), "--config", second.toString());
        assertEquals(2, result.code());
    }
}
