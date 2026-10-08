package org.wanaku.wsr;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** File settings precede environment settings, generic properties, and explicit command options. */
final class DeploymentOptions {
    private static final Map<String, String> ENVIRONMENT_SETTINGS = Map.ofEntries(
            Map.entry("WSR_SEMANTIC_ROUTE", "wsr.semantic-route"),
            Map.entry("WSR_BARN_URL", "wsr.barn.url"),
            Map.entry("WSR_REGISTRATION_URL", "wsr.wanaku.url"),
            Map.entry("WSR_MCP_ADDRESS", "wsr.mcp.address"),
            Map.entry("WSR_BIND", "wsr.bind"),
            Map.entry("WSR_MCP_PORT", "wsr.port"),
            Map.entry("WSR_NAME", "wsr.forward.name"),
            Map.entry("WSR_NAMESPACE", "wsr.forward.namespace"),
            Map.entry("WSR_DATA_DIR", "wsr.work-directory"),
            Map.entry("WSR_BARN_TOKEN_ENV", "wsr.barn.token-env"),
            Map.entry("WSR_WANAKU_TOKEN_ENV", "wsr.wanaku.token-env"),
            Map.entry("WSR_CATALOG_NAME", "wsr.catalog.name"),
            Map.entry("WSR_CATALOG_SERVICE", "wsr.catalog.service"),
            Map.entry("WSR_CATALOG_REVISION", "wsr.catalog.revision"),
            Map.entry("WSR_CATALOG_SHA256", "wsr.catalog.sha256"),
            Map.entry("WSR_CATALOG_MAIN", "wsr.catalog.main"),
            Map.entry("WSR_STATUS_PORT", "wsr.status.port"),
            Map.entry("WSR_STATUS_BIND", "wsr.status.bind"),
            Map.entry("WSR_HTTP_TIMEOUT_MS", "wsr.http.timeout-ms"));

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "DEPLOYMENT",
            description = "Optional deployment properties file.")
    private String deploymentFile;

    @CommandLine.Option(
            names = "--config",
            paramLabel = "FILE",
            description = "Deployment properties file, as an alternative to DEPLOYMENT.")
    private String configFile;

    @CommandLine.Spec
    private CommandSpec spec;

    private final Properties properties = new Properties();
    private final Map<String, String> experts = new LinkedHashMap<>();

    @CommandLine.Option(
            names = {"--property", "-p"},
            arity = "1",
            paramLabel = "KEY=VALUE",
            description = "Deployment or Camel component property. Repeat for additional settings.")
    private void property(List<String> values) {
        try {
            for (String value : values) {
                String[] assignment = assignment(value);
                properties.setProperty(assignment[0], assignment[1]);
            }
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Invalid --property. Use KEY=VALUE with a nonblank key.");
        }
    }

    @CommandLine.Option(
            names = "--expert",
            arity = "1",
            paramLabel = "NAME=CLASS",
            description = "Enable an expert bean. Repeat to enable multiple experts.")
    private void expert(List<String> values) {
        try {
            for (String value : values) {
                String[] assignment = assignment(value);
                String bean = RuntimeSettings.identifier(assignment[0]);
                String className = assignment[1].trim();
                if (!className.matches("[A-Za-z_$][A-Za-z0-9_$]*([.][A-Za-z_$][A-Za-z0-9_$]*)*")) {
                    throw new IllegalArgumentException("Invalid expert class");
                }
                experts.put(bean, className);
            }
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Invalid --expert. Use NAME=CLASS with a valid bean and Java class name.");
        }
    }

    private static String[] assignment(String value) {
        int separator = value.indexOf('=');
        if (separator < 1) {
            throw new IllegalArgumentException("Invalid assignment");
        }
        String key = value.substring(0, separator).trim();
        if (key.isBlank() || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid assignment key");
        }
        return new String[] {key, value.substring(separator + 1)};
    }

    Properties load(Initialization initialization, CommandSpec command, Properties named) throws Exception {
        return load(initialization, command, named, System.getenv());
    }

    Properties load(
            Initialization initialization, CommandSpec command, Properties named, Map<String, String> environment)
            throws Exception {
        if (deploymentFile != null && configFile != null) {
            throw new CommandLine.ParameterException(command.commandLine(), "Use either DEPLOYMENT or --config.");
        }
        String file = configFile == null ? deploymentFile : configFile;
        Path path;
        try {
            path = file == null ? null : Path.of(file);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(command.commandLine(), "Cannot read deployment properties file.");
        }
        String dataDirectory = environment.get("WSR_DATA_DIR");
        if (dataDirectory != null
                && !dataDirectory.isBlank()
                && (command.commandLine().getParseResult() == null
                        || !command.commandLine().getParseResult().hasMatchedOption("--data-dir"))) {
            try {
                initialization.dataDirectory = Path.of(dataDirectory);
            } catch (IllegalArgumentException e) {
                throw new CommandLine.ParameterException(command.commandLine(), "Invalid WSR_DATA_DIR.");
            }
        }
        Properties deployment = CliConfiguration.read(initialization.prepare(path, command), command);
        environment(deployment, environment);
        deployment.putAll(properties);
        if (!experts.isEmpty()) {
            deployment.setProperty("wsr.experts", String.join(",", experts.keySet()));
            experts.forEach((bean, type) -> deployment.setProperty("wsr.expert." + bean + ".class", type));
        }
        deployment.putAll(named);
        return deployment;
    }

    /** Explicit mappings keep deployment environment settings bounded and testable. */
    static void environment(Properties deployment, Map<String, String> environment) {
        ENVIRONMENT_SETTINGS.forEach((variable, property) -> {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                deployment.setProperty(property, value);
            }
        });
        String workDirectory = environment.get("WSR_WORK_DIRECTORY");
        if (workDirectory != null && !workDirectory.isBlank()) {
            deployment.setProperty("wsr.work-directory", workDirectory);
        }
    }
}
