package org.wanaku.wsr;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@CommandLine.Command(
        name = "preview",
        description = "Start an isolated semantic evaluation service without loading action routes.",
        mixinStandardHelpOptions = true,
        versionProvider = Application.Version.class)
final class PreviewCommand implements Callable<Integer> {
    @CommandLine.Mixin
    private DeploymentOptions options = new DeploymentOptions();

    private final Properties named = new Properties();

    @CommandLine.Mixin
    private Initialization initialization = new Initialization();

    @CommandLine.Spec
    private CommandSpec spec;

    @CommandLine.Option(names = "--bind", description = "Preview listener address. Default: 127.0.0.1.")
    private void bind(String value) {
        named.setProperty("wsr.preview.bind", value);
    }

    @CommandLine.Option(
            names = {"--port", "--preview-port"},
            description = "Preview listener port. Default: 8092.")
    private void port(String value) {
        named.setProperty("wsr.preview.port", value);
    }

    @CommandLine.Option(
            names = "--preview-timeout-ms",
            description = "Evaluation timeout in milliseconds. Default: 10000.")
    private void timeout(String value) {
        named.setProperty("wsr.preview.timeout-ms", value);
    }

    @CommandLine.Option(names = "--max-concurrent", description = "Concurrent evaluations. Default: 4.")
    private void concurrency(String value) {
        named.setProperty("wsr.preview.max-concurrent", value);
    }

    @CommandLine.Option(
            names = "--preview-token-env",
            description = "Environment variable containing the preview token.")
    private void tokenEnvironment(String value) {
        named.setProperty("wsr.preview.token-env", value);
    }

    Properties deployment() throws Exception {
        return options.load(initialization, spec, named);
    }

    @Override
    public Integer call() throws Exception {
        var deployment = deployment();
        PreviewService preview;
        try {
            CliConfiguration.experts(deployment);
            CliConfiguration.port(deployment, "wsr.preview.port", 8092);
            preview = new PreviewService(deployment);
        } catch (IllegalArgumentException e) {
            throw CliConfiguration.invalid(spec, e);
        }
        try (preview) {
            return run(preview);
        }
    }

    private int run(PreviewService preview) throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> shutdown(preview, stopped), "wsr-preview-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            preview.start();
            stopped.await();
            return CommandLine.ExitCode.OK;
        } finally {
            CliConfiguration.removeHook(hook);
        }
    }

    private void shutdown(PreviewService preview, CountDownLatch stopped) {
        try {
            preview.close();
        } finally {
            stopped.countDown();
        }
    }
}
