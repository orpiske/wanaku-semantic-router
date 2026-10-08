package org.wanaku.wsr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** Configuration errors expose required keys but never supplied values or exception details. */
final class CliConfiguration {
    private CliConfiguration() {}

    static Properties read(Path file, CommandSpec spec) {
        if (file == null) {
            return new Properties();
        }
        try {
            return CatalogLoader.properties(file);
        } catch (IOException | IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Cannot read deployment properties file.");
        }
    }

    static void experts(Properties deployment) {
        for (String bean : RuntimeSettings.required(deployment, "wsr.experts").split(",", -1)) {
            bean = RuntimeSettings.identifier(bean.trim());
            RuntimeSettings.required(deployment, "wsr.expert." + bean + ".class");
        }
    }

    static int port(Properties deployment, String key, int fallback) {
        int value = Integer.parseInt(deployment.getProperty(key, Integer.toString(fallback)));
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("Invalid deployment port");
        }
        return value;
    }

    static CommandLine.ParameterException invalid(CommandSpec spec, IllegalArgumentException error) {
        String message = error.getMessage();
        String detail = message != null && message.startsWith("Missing setting: ")
                ? message
                : "Check settings and credential environment variables.";
        return new CommandLine.ParameterException(spec.commandLine(), "Invalid deployment configuration. " + detail);
    }

    static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // A signal can start JVM shutdown while the command finishes.
        }
    }
}
