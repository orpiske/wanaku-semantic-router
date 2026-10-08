package org.wanaku.wsr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@CommandLine.Command(
        name = "runtime",
        description = "Fetch a fixed Barn catalog, start Camel MCP, then register its Wanaku forward.",
        mixinStandardHelpOptions = true,
        versionProvider = Application.Version.class)
final class RuntimeCommand implements Callable<Integer> {
    @CommandLine.Mixin
    private DeploymentOptions options = new DeploymentOptions();

    private final Properties named = new Properties();

    @CommandLine.Mixin
    private Initialization initialization = new Initialization();

    @CommandLine.Spec
    private CommandSpec spec;

    @CommandLine.Option(
            names = "--semantic-route",
            description = "Resolve one published semantic route by its exact Barn name.")
    private void semanticRoute(String value) {
        named.setProperty("wsr.semantic-route", value);
    }

    @CommandLine.Option(names = "--barn-url", description = "Barn management URL.")
    private void barnUrl(String value) {
        named.setProperty("wsr.barn.url", value);
    }

    @CommandLine.Option(
            names = {"--registration-url", "--wanaku-url"},
            description = "Wanaku management URL for forward registration.")
    private void registrationUrl(String value) {
        named.setProperty("wsr.wanaku.url", value);
    }

    @CommandLine.Option(
            names = {"--service-catalog", "--catalog-name"},
            description = "Barn service catalog name.")
    private void catalogName(String value) {
        named.setProperty("wsr.catalog.name", value);
    }

    @CommandLine.Option(
            names = {"--service-catalog-system", "--catalog-service"},
            description = "Catalog service name. Default: service.")
    private void catalogService(String value) {
        named.setProperty("wsr.catalog.service", value);
    }

    @CommandLine.Option(names = "--catalog-revision", description = "Fixed catalog revision.")
    private void catalogRevision(String value) {
        named.setProperty("wsr.catalog.revision", value);
    }

    @CommandLine.Option(names = "--catalog-sha256", description = "Expected catalog archive SHA-256 digest.")
    private void catalogDigest(String value) {
        named.setProperty("wsr.catalog.sha256", value);
    }

    @CommandLine.Option(names = "--catalog-main", description = "Optional main route selection inside the catalog.")
    private void catalogMain(String value) {
        named.setProperty("wsr.catalog.main", value);
    }

    @CommandLine.Option(
            names = {"--name", "--forward-name"},
            description = "Wanaku forward name.")
    private void forwardName(String value) {
        named.setProperty("wsr.forward.name", value);
    }

    @CommandLine.Option(names = "--namespace", description = "Wanaku namespace. Default: default.")
    private void namespace(String value) {
        named.setProperty("wsr.forward.namespace", value);
    }

    @CommandLine.Option(names = "--mcp-address", description = "Advertised MCP endpoint URL.")
    private void mcpAddress(String value) {
        named.setProperty("wsr.mcp.address", value);
    }

    @CommandLine.Option(
            names = {"--mcp-port", "--port"},
            description = "MCP listener port. Default: 8090.")
    private void mcpPort(String value) {
        named.setProperty("wsr.port", value);
    }

    @CommandLine.Option(names = "--bind", description = "MCP listener address. Default: 127.0.0.1.")
    private void bind(String value) {
        named.setProperty("wsr.bind", value);
    }

    @CommandLine.Option(names = "--status-port", description = "Status listener port. Default: 8091.")
    private void statusPort(String value) {
        named.setProperty("wsr.status.port", value);
    }

    @CommandLine.Option(names = "--status-bind", description = "Status listener address. Default: 127.0.0.1.")
    private void statusBind(String value) {
        named.setProperty("wsr.status.bind", value);
    }

    @CommandLine.Option(
            names = "--http-timeout-ms",
            description = "HTTP completion timeout in milliseconds. Default: 30000.")
    private void httpTimeout(String value) {
        named.setProperty("wsr.http.timeout-ms", value);
    }

    @CommandLine.Option(names = "--work-directory", description = "Directory for private catalog extraction.")
    private void workDirectory(String value) {
        named.setProperty("wsr.work-directory", value);
    }

    @CommandLine.Option(names = "--barn-token-env", description = "Environment variable containing the Barn token.")
    private void barnTokenEnvironment(String value) {
        named.setProperty("wsr.barn.token-env", value);
    }

    @CommandLine.Option(names = "--wanaku-token-env", description = "Environment variable containing the Wanaku token.")
    private void wanakuTokenEnvironment(String value) {
        named.setProperty("wsr.wanaku.token-env", value);
    }

    Properties deployment() throws Exception {
        return deployment(System.getenv());
    }

    Properties deployment(Map<String, String> environment) throws Exception {
        Properties overrides = new Properties();
        overrides.putAll(named);
        if (spec.commandLine().getParseResult() != null
                && spec.commandLine().getParseResult().hasMatchedOption("--data-dir")
                && !overrides.containsKey("wsr.work-directory")) {
            overrides.setProperty("wsr.work-directory", initialization.dataDirectory.toString());
        }
        Properties deployment = options.load(initialization, spec, overrides, environment);
        if (deployment.containsKey("wsr.semantic-route")) {
            SemanticRouteResolver.resolve(deployment, environment);
        } else {
            deployment.putIfAbsent("wsr.catalog.service", "service");
        }
        deployment.putIfAbsent("wsr.forward.namespace", "default");
        return deployment;
    }

    @Override
    public Integer call() throws Exception {
        Properties deployment;
        RuntimeSettings settings;
        int statusPort;
        try {
            deployment = deployment();
            settings = RuntimeSettings.from(deployment);
            CliConfiguration.experts(deployment);
            statusPort = CliConfiguration.port(deployment, "wsr.status.port", 8091);
        } catch (IllegalArgumentException e) {
            throw CliConfiguration.invalid(spec, e);
        }
        try (var runtime = new SemanticRuntime(settings)) {
            return run(runtime, createStatusServer(runtime, deployment, statusPort));
        }
    }

    private HttpServer createStatusServer(SemanticRuntime runtime, Properties deployment, int port) throws IOException {
        HttpServer status = HttpServer.create(
                new InetSocketAddress(deployment.getProperty("wsr.status.bind", "127.0.0.1"), port), 16);
        status.createContext("/api/v1/status", exchange -> PreviewService.respond(exchange, 200, runtime.status()));
        return status;
    }

    private int run(SemanticRuntime runtime, HttpServer status) throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> shutdown(runtime, status, stopped), "wsr-runtime-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            status.start();
            startRuntime(runtime);
            stopped.await();
            return CommandLine.ExitCode.OK;
        } finally {
            CliConfiguration.removeHook(hook);
            status.stop(1);
        }
    }

    private void startRuntime(SemanticRuntime runtime) throws Exception {
        try {
            runtime.start();
        } catch (Exception e) {
            spec.commandLine().getErr().println(new ObjectMapper().writeValueAsString(runtime.status()));
            throw e;
        }
    }

    private void shutdown(SemanticRuntime runtime, HttpServer status, CountDownLatch stopped) {
        try {
            runtime.close();
        } catch (Exception e) {
            spec.commandLine()
                    .getErr()
                    .println("WSR shutdown failed: " + e.getClass().getSimpleName());
        } finally {
            status.stop(1);
            stopped.countDown();
        }
    }
}
