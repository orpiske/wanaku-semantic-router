package org.wanaku.wsr;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import ai.wanaku.capabilities.sdk.common.exceptions.WanakuWebException;
import ai.wanaku.capabilities.sdk.runtime.camel.downloader.ExponentialBackoffRetryPolicy;
import ai.wanaku.capabilities.sdk.runtime.camel.downloader.RetryPolicy;
import ai.wanaku.capabilities.sdk.runtime.camel.init.InitializerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** CIC-style SDK initialization with bounded helper lifetime and atomic checkout publication. */
final class Initialization {
    @CommandLine.Option(
            names = "--init-from",
            paramLabel = "SOURCE",
            description = "Initialize deployment files from a credential-free HTTPS, SSH, or file Git source.")
    String source;

    @CommandLine.Option(
            names = "--data-dir",
            paramLabel = "DIRECTORY",
            description = "Directory for the initialized checkout and runtime catalog extraction.",
            defaultValue = "${sys:user.home}/.wanaku/wsr")
    Path dataDirectory = Path.of(System.getProperty("user.home"), ".wanaku", "wsr");

    @CommandLine.Option(names = "--retries", description = "Maximum initialization retries (0-12).", defaultValue = "3")
    int retries = 3;

    @CommandLine.Option(
            names = "--wait-seconds",
            description = "Initial retry delay in seconds (1-30); exponential delays have a 30-second limit.",
            defaultValue = "5")
    int waitSeconds = 5;

    @CommandLine.Option(
            names = "--init-timeout-seconds",
            description = "Complete initialization timeout in seconds, including retries (1-600).",
            defaultValue = "120")
    int timeoutSeconds = 120;

    private final List<String> helperCommand;

    Initialization() {
        this(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                InitializationWorker.class.getName()));
    }

    /** A command prefix permits deterministic process-lifetime tests without a remote Git server. */
    Initialization(List<String> helperCommand) {
        this.helperCommand = List.copyOf(helperCommand);
    }

    Path prepare(Path deployment, CommandSpec spec) throws Exception {
        if (retries < 0
                || retries > 12
                || waitSeconds < 1
                || waitSeconds > 30
                || timeoutSeconds < 1
                || timeoutSeconds > 600) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Invalid initialization limits.");
        }
        if (source == null) {
            InitializerFactory.createInitializer(null, dataDirectory).initialize();
            return deployment;
        }
        String validated;
        try {
            validated = validatedSource(source);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Invalid initialization source. Use a credential-free Git source.");
        }
        Path checkout = initialize(validated);
        if (deployment == null) {
            return null;
        }
        Path selected = deployment.isAbsolute()
                ? deployment.normalize()
                : checkout.resolve(deployment).normalize();
        if (!deployment.isAbsolute() && !selected.startsWith(checkout)) {
            throw outsideCheckout(spec);
        }
        try {
            Path real = selected.toRealPath();
            Path configuredCheckout = dataDirectory.toAbsolutePath().normalize().resolve("cloned-repo");
            if ((selected.startsWith(checkout)
                            || selected.startsWith(configuredCheckout)
                            || (selected.getParent() != null
                                    && selected.getParent().toRealPath().startsWith(checkout)))
                    && !real.startsWith(checkout)) {
                throw outsideCheckout(spec);
            }
            return real;
        } catch (IOException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Cannot read deployment properties file.");
        }
    }

    private static CommandLine.ParameterException outsideCheckout(CommandSpec spec) {
        return new CommandLine.ParameterException(
                spec.commandLine(), "Deployment must remain inside the initialized repository.");
    }

    static String validatedSource(String source) {
        String value = source.trim();
        if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidSource();
        }
        if (value.matches("(?:[A-Za-z0-9_][A-Za-z0-9_.-]*@)?[A-Za-z0-9][A-Za-z0-9.-]*:(?!//)[A-Za-z0-9_./~-]+")) {
            return value;
        }
        try {
            URI uri = new URI(value);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidSource();
            }
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                if (uri.getRawAuthority() != null || !Path.of(uri).isAbsolute()) {
                    throw invalidSource();
                }
            } else if ("https".equals(scheme) || "ssh".equals(scheme)) {
                if (uri.getHost() == null
                        || uri.getPath() == null
                        || uri.getPath().isEmpty()) {
                    throw invalidSource();
                }
                String user = uri.getUserInfo();
                if (user != null && ("https".equals(scheme) || !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) {
                    throw invalidSource();
                }
            } else {
                throw invalidSource();
            }
            return value;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw invalidSource();
        }
    }

    private static IllegalArgumentException invalidSource() {
        return new IllegalArgumentException("Invalid Git source");
    }

    private Path initialize(String validated) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Files.createDirectories(dataDirectory);
        Path root = dataDirectory.toRealPath();
        try (FileChannel channel = FileChannel.open(
                        root.resolve(".wsr-init.lock"),
                        Set.<OpenOption>of(
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        permissions(root, "rw-------"));
                FileLock ignored = lock(channel, deadline)) {
            Path destination = root.resolve("cloned-repo");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                return checkedCheckout(destination, validated);
            }
            RetryPolicy policy = ExponentialBackoffRetryPolicy.newBuilder()
                    .maxRetries(retries)
                    .initialDelayMillis(TimeUnit.SECONDS.toMillis(waitSeconds))
                    .maxDelayMillis(TimeUnit.SECONDS.toMillis(30))
                    .build();
            for (int attempt = 0; ; attempt++) {
                Exception failure;
                try (Attempt clone = new Attempt(root)) {
                    Process child = clone.start(helperCommand, validated);
                    if (!child.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                        throw new IOException("Initialization timed out");
                    }
                    if (child.exitValue() == 0) {
                        Path staged = checkedCheckout(clone.directory.resolve("cloned-repo"), validated);
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            return checkedCheckout(destination, validated);
                        }
                        if (Files.getFileStore(staged).supportsFileAttributeView("posix")) {
                            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwx------"));
                        }
                        remaining(deadline);
                        Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
                        return destination;
                    }
                    failure = child.exitValue() == 75
                            ? new WanakuWebException(503)
                            : new IOException("Initialization failed");
                }
                if (attempt >= policy.maxRetries() || !policy.isRetryable(failure)) {
                    throw new IOException("Initialization failed");
                }
                long delay = TimeUnit.MILLISECONDS.toNanos(policy.getDelayMillis(attempt + 1));
                TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining(deadline)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static FileLock lock(FileChannel channel, long deadline) throws Exception {
        while (true) {
            remaining(deadline);
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // A command in this JVM can hold the same deployment-directory lock.
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25), remaining(deadline)));
        }
    }

    private static long remaining(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IOException("Initialization timed out");
        }
        return remaining;
    }

    private static Path checkedCheckout(Path directory, String source) throws IOException {
        if (Files.isSymbolicLink(directory)
                || Files.isSymbolicLink(directory.resolve(".git"))
                || !Files.isDirectory(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Initialization destination is not a Git checkout");
        }
        Path real = directory.toRealPath();
        try (Git git = Git.open(real.toFile())) {
            if (git.getRepository().isBare()
                    || !git.getRepository().getWorkTree().toPath().toRealPath().equals(real)
                    || !sameOrigin(source, git.getRepository().getConfig().getString("remote", "origin", "url"))) {
                throw new IOException("Initialization destination has a different Git origin");
            }
        }
        return real;
    }

    private static boolean sameOrigin(String source, String origin) {
        try {
            return origin != null && new URIish(source).equals(new URIish(origin));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static FileAttribute<?>[] permissions(Path parent, String mode) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))}
                : new FileAttribute<?>[0];
    }

    /** The process must exit before cleanup releases its private staging directory. */
    private static final class Attempt implements AutoCloseable {
        private final Path directory;
        private final Path log;
        private final Thread shutdown;
        private Process process;
        private boolean closed;

        Attempt(Path root) throws IOException {
            directory = Files.createTempDirectory(root, ".wsr-init-", permissions(root, "rwx------"));
            log = directory.resolve("initializer.log");
            try {
                Files.createFile(log, permissions(directory, "rw-------"));
                shutdown = new Thread(
                        () -> {
                            try {
                                close();
                            } catch (IOException ignored) {
                                // Shutdown cannot expose helper logs or configuration values.
                            }
                        },
                        "wsr-initialization-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
            } catch (IOException | RuntimeException e) {
                delete(directory);
                throw e;
            }
        }

        synchronized Process start(List<String> prefix, String source) throws IOException {
            if (closed) {
                throw new IOException("Initialization stopped");
            }
            List<String> command = new ArrayList<>(prefix);
            command.add(source);
            command.add(directory.toString());
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            return process;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            CliConfiguration.removeHook(shutdown);
            if (process != null) {
                boolean interrupted = Thread.interrupted();
                // SSH transports can spawn external processes. Stop leaves while their parents
                // can still reap them, then stop the helper before deleting its staging files.
                List<ProcessHandle> children;
                try (var descendants = process.descendants()) {
                    children = descendants
                            .sorted(Comparator.comparingInt(Initialization::processDepth)
                                    .reversed())
                            .toList();
                }
                for (ProcessHandle child : children) {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                    }
                    child.onExit().join();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                while (true) {
                    try {
                        process.waitFor();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            delete(directory);
        }
    }

    private static int processDepth(ProcessHandle process) {
        int depth = 0;
        for (var parent = process.parent();
                parent.isPresent();
                parent = parent.get().parent()) {
            depth++;
        }
        return depth;
    }

    private static void delete(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
