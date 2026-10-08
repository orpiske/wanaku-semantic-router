package org.wanaku.wsr;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** CLI entry point follows Camel Integration Capability's annotated Callable pattern. */
@CommandLine.Command(
        name = "wsr",
        description = "Run a pinned Barn catalog or an isolated classification preview service.",
        mixinStandardHelpOptions = true,
        versionProvider = Application.Version.class,
        subcommands = {RuntimeCommand.class, PreviewCommand.class})
public final class Application implements Callable<Integer> {
    public static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String release = Application.class.getPackage().getImplementationVersion();
            return new String[] {
                "WSR " + (release == null ? "development" : release), "Camel " + CatalogLoader.CAMEL_VERSION
            };
        }
    }

    @CommandLine.Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    static CommandLine commandLine() {
        return new CommandLine(new Application()).setExecutionExceptionHandler((failure, command, parseResult) -> {
            if (failure instanceof CommandLine.ParameterException parameter) {
                command.getErr().println(parameter.getMessage());
                command.usage(command.getErr());
                return CommandLine.ExitCode.USAGE;
            }
            // Provider errors can contain credentials or submitted data. Do not print their
            // messages.
            command.getErr().println("WSR command failed: " + failure.getClass().getSimpleName());
            return CommandLine.ExitCode.SOFTWARE;
        });
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return CommandLine.ExitCode.USAGE;
    }
}
