package org.wanaku.wsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class InitializationTest {
    private static final String CONTENT = "wsr.test=pinned-fixture\n";

    @TempDir
    Path directory;

    private static CommandSpec spec() {
        return new CommandLine(new RuntimeCommand()).getCommandSpec();
    }

    private Path repository(String name) throws Exception {
        Path repository = Files.createDirectory(directory.resolve(name));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.createDirectories(repository.resolve("config"));
            Files.writeString(repository.resolve("config/runtime.properties"), CONTENT);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setSign(false)
                    .setMessage("Fixture")
                    .setAuthor("Fixture", "fixture@localhost")
                    .setCommitter("Fixture", "fixture@localhost")
                    .call();
        }
        return repository;
    }

    private Initialization configured(Path repository) {
        Initialization initialization = new Initialization();
        initialization.source = repository.toUri().toString();
        initialization.dataDirectory = directory.resolve("data");
        initialization.retries = 0;
        return initialization;
    }

    private void assertNoStaging(Path data) throws IOException {
        try (var files = Files.list(data)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".wsr-init-")));
        }
    }

    @Test
    void absentSourceUsesSdkNoOpWithoutTouchingDataDirectory() throws Exception {
        Initialization initialization = new Initialization();
        initialization.dataDirectory = directory.resolve("absent-data");
        Path deployment = Path.of("external.properties");
        assertEquals(deployment, initialization.prepare(deployment, spec()));
        assertFalse(Files.exists(initialization.dataDirectory));
    }

    @Test
    void sdkCloneResolvesRelativeConfigurationAndReusesTheOriginalCheckout() throws Exception {
        Path source = repository("source");
        Initialization initialization = configured(source);
        Path deployment = initialization.prepare(Path.of("config/runtime.properties"), spec());
        Path checkout = initialization.dataDirectory.resolve("cloned-repo").toRealPath();
        assertEquals(checkout.resolve("config/runtime.properties"), deployment);
        assertEquals(CONTENT, Files.readString(deployment));
        try (Git git = Git.open(source.toFile())) {
            Files.writeString(source.resolve("config/runtime.properties"), "wsr.test=updated\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setSign(false)
                    .setMessage("Source update")
                    .setAuthor("Fixture", "fixture@localhost")
                    .setCommitter("Fixture", "fixture@localhost")
                    .call();
        }
        assertEquals(deployment, initialization.prepare(Path.of("config/runtime.properties"), spec()));
        assertEquals(CONTENT, Files.readString(deployment));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void localFileSourcesCanUseEitherSingleSlashOrTripleSlashUriForms() throws Exception {
        Initialization initialization = configured(repository("source"));
        String tripleSlash = initialization.source;
        initialization.source = tripleSlash.replaceFirst("^file:///", "file:/");
        Path deployment = initialization.prepare(Path.of("config/runtime.properties"), spec());
        assertEquals(CONTENT, Files.readString(deployment));
        initialization.source = tripleSlash;
        assertEquals(deployment, initialization.prepare(Path.of("config/runtime.properties"), spec()));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void conflictingOriginAndNonGitDestinationArePreserved() throws Exception {
        Initialization initialization = configured(repository("first"));
        Path deployment = initialization.prepare(Path.of("config/runtime.properties"), spec());
        initialization.source = repository("second").toUri().toString();
        assertThrows(IOException.class, () -> initialization.prepare(Path.of("config/runtime.properties"), spec()));
        assertEquals(CONTENT, Files.readString(deployment));
        assertNoStaging(initialization.dataDirectory);

        initialization.dataDirectory = directory.resolve("non-git-data");
        Path destination = Files.createDirectories(initialization.dataDirectory.resolve("cloned-repo"));
        Path marker = Files.writeString(destination.resolve("preserve.txt"), "operator-owned");
        assertThrows(IOException.class, () -> initialization.prepare(Path.of("config/runtime.properties"), spec()));
        assertEquals("operator-owned", Files.readString(marker));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void relativeTraversalAndSymlinkEscapesFailButAnExternalAbsoluteConfigurationWorks() throws Exception {
        Initialization initialization = configured(repository("source"));
        initialization.prepare(Path.of("config/runtime.properties"), spec());
        Path outside = Files.writeString(directory.resolve("outside.properties"), "external\n")
                .toRealPath();
        assertThrows(
                CommandLine.ParameterException.class,
                () -> initialization.prepare(Path.of("../outside.properties"), spec()));
        Path link = initialization.dataDirectory.resolve("cloned-repo/escape.properties");
        Files.createSymbolicLink(link, outside);
        assertThrows(
                CommandLine.ParameterException.class,
                () -> initialization.prepare(Path.of("escape.properties"), spec()));
        assertThrows(CommandLine.ParameterException.class, () -> initialization.prepare(link, spec()));
        assertEquals(outside, initialization.prepare(outside, spec()));
    }

    @Test
    void onlyCredentialFreeSupportedGitSourcesAreAccepted() {
        for (String valid : List.of(
                "https://github.com/wanaku-ai/example.git",
                "ssh://git@github.com/wanaku-ai/example.git",
                "git@github.com:wanaku-ai/example.git",
                directory.toUri().toString())) {
            assertEquals(valid, Initialization.validatedSource(valid));
        }
        for (String invalid : List.of(
                "",
                "http://github.com/example.git",
                "file://remote/example",
                "https://user:secret@example.com/repo",
                "https://user@example.com/repo",
                "https://example.com/repo?token=secret",
                "https://example.com/repo#secret",
                "ssh://git:secret@example.com/repo",
                "git:secret@example.com:repo",
                "git@example.com:repo?secret",
                "ssh://git%3Asecret@example.com/repo",
                "https://example.com/repo\nsecret")) {
            assertThrows(IllegalArgumentException.class, () -> Initialization.validatedSource(invalid));
        }
    }

    private Initialization withWorker(Path source, String mode, Path marker) {
        Initialization initialization = new Initialization(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FixtureWorker.class.getName(),
                mode,
                marker.toString()));
        initialization.source = source.toUri().toString();
        initialization.dataDirectory = directory.resolve("data");
        initialization.waitSeconds = 1;
        return initialization;
    }

    @Test
    void sdkRetryPolicyRetriesTemporaryHelperFailureAndRejectsPermanentFailure() throws Exception {
        Path source = repository("source");
        Path counter = directory.resolve("attempts");
        Initialization initialization = withWorker(source, "retry", counter);
        initialization.retries = 1;
        Path deployment = initialization.prepare(Path.of("config/runtime.properties"), spec());
        assertEquals(CONTENT, Files.readString(deployment));
        assertEquals("2", Files.readString(counter));
        assertNoStaging(initialization.dataDirectory);

        initialization = withWorker(source, "permanent", counter);
        initialization.dataDirectory = directory.resolve("permanent-data");
        Files.delete(counter);
        Initialization failed = initialization;
        assertThrows(IOException.class, () -> failed.prepare(Path.of("config/runtime.properties"), spec()));
        assertEquals("1", Files.readString(counter));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void helperDeadlineKillsAndWaitsBeforeDeletingPartialClone() throws Exception {
        Path pid = directory.resolve("helper.pid");
        Initialization initialization = withWorker(repository("source"), "sleep", pid);
        initialization.timeoutSeconds = 2;
        assertThrows(IOException.class, () -> initialization.prepare(Path.of("config/runtime.properties"), spec()));
        assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid)))
                .map(ProcessHandle::isAlive)
                .orElse(false));
        assertFalse(Files.exists(initialization.dataDirectory.resolve("cloned-repo")));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void deadlineStopsTheHelperAndItsExternalTransportBeforeCleanup() throws Exception {
        Path pid = directory.resolve("helper.pid");
        Initialization initialization = withWorker(repository("source"), "spawn-child", pid);
        initialization.timeoutSeconds = 3;
        assertThrows(IOException.class, () -> initialization.prepare(Path.of("config/runtime.properties"), spec()));
        for (Path marker : List.of(pid, Path.of(pid + ".child"))) {
            assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(marker)))
                    .map(ProcessHandle::isAlive)
                    .orElse(false));
        }
        assertFalse(Files.exists(initialization.dataDirectory.resolve("cloned-repo")));
        assertNoStaging(initialization.dataDirectory);
    }

    @Test
    void interruptedInitializationWaitsForTheHelperAndPreservesInterruptStatus() throws Exception {
        Path pid = directory.resolve("helper.pid");
        Initialization initialization = withWorker(repository("source"), "sleep", pid);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                initialization.prepare(Path.of("config/runtime.properties"), spec());
            } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(pid) && thread.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(pid));
            thread.interrupt();
            thread.join(5000);
            assertFalse(thread.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertTrue(interrupted.get());
            assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid)))
                    .map(ProcessHandle::isAlive)
                    .orElse(false));
            assertNoStaging(initialization.dataDirectory);
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    void overallDeadlineIncludesWaitingForAnotherInitializerLock() throws Exception {
        Initialization initialization = configured(repository("source"));
        initialization.timeoutSeconds = 1;
        Files.createDirectories(initialization.dataDirectory);
        try (FileChannel channel = FileChannel.open(
                        initialization.dataDirectory.resolve(".wsr-init.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            assertThrows(IOException.class, () -> initialization.prepare(Path.of("config/runtime.properties"), spec()));
            assertFalse(Files.exists(initialization.dataDirectory.resolve("cloned-repo")));
            assertNoStaging(initialization.dataDirectory);
        }
    }

    /** A separate JVM makes timeout and retry behavior deterministic without remote I/O. */
    public static final class FixtureWorker {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[1]);
            if ("sleep".equals(args[0]) || "spawn-child".equals(args[0])) {
                if ("spawn-child".equals(args[0])) {
                    Path childMarker = Path.of(marker + ".child");
                    Process child = new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    TransportWorker.class.getName(),
                                    childMarker.toString())
                            .start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!Files.exists(childMarker) && child.isAlive() && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    if (!Files.exists(childMarker)) {
                        throw new IOException("Transport fixture did not start");
                    }
                }
                Files.createDirectory(Path.of(args[3]).resolve("cloned-repo"));
                Files.writeString(Path.of(args[3]).resolve("cloned-repo/partial"), "incomplete");
                Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
                while (true) {
                    Thread.sleep(1000);
                }
            }
            int attempt = Files.exists(marker) ? Integer.parseInt(Files.readString(marker)) + 1 : 1;
            Files.writeString(marker, Integer.toString(attempt));
            if ("retry".equals(args[0]) && attempt == 1) {
                System.exit(75);
            }
            if ("permanent".equals(args[0])) {
                System.exit(1);
            }
            InitializationWorker.main(new String[] {args[2], args[3]});
        }
    }

    public static final class TransportWorker {
        public static void main(String[] args) throws Exception {
            Files.writeString(
                    Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            while (true) {
                Thread.sleep(1000);
            }
        }
    }
}
