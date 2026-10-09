package org.wanaku.wsr;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.camel.main.Main;

/** Startup ordering is explicit: catalog -> dependencies -> Camel/MCP -> discovery -> forward. */
public final class SemanticRuntime implements AutoCloseable {
    private final RuntimeSettings settings;
    // Blocking startup and cleanup must not pin Java 21 virtual-thread carriers on a monitor.
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile Thread startupThread;
    private boolean startAttempted;
    private CatalogLoader.Catalog catalog;
    private Main main;
    private Dependencies dependencies;
    private ForwardRegistration registration;
    private volatile String state = "starting";
    private volatile String failure;

    public SemanticRuntime(RuntimeSettings settings) {
        this.settings = settings;
    }

    public void start() throws Exception {
        lifecycle.lock();
        try {
            if (stopping.get() || startAttempted) {
                throw new IllegalStateException("Runtime startup is no longer available");
            }
            startAttempted = true;
            startupThread = Thread.currentThread();
            try {
                startResources();
                state = "ready";
            } catch (Exception e) {
                failure = e.getClass().getSimpleName();
                state = "failed";
                try {
                    closeResources();
                } catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            } finally {
                startupThread = null;
            }
        } finally {
            lifecycle.unlock();
        }
    }

    private void startResources() throws Exception {
        checkCancellation();
        catalog = new CatalogLoader().fetch(settings.catalog(), settings.runtime());
        checkCancellation();
        dependencies = Dependencies.load(catalog.dependencies(), catalog.root().resolve("resolved-dependencies"));
        checkCancellation();
        startCamel();
        String readinessHost =
                switch (settings.runtime().bind()) {
                    case "0.0.0.0" -> "127.0.0.1";
                    case "::" -> "::1";
                    default -> settings.runtime().bind();
                };
        checkCancellation();
        McpReadiness.verify(
                new URI("http", null, readinessHost, settings.runtime().port(), "/mcp", null, null),
                catalog.manifest().getProperty("tool.name"));
        checkCancellation();
        registration = new ForwardRegistration(
                settings.registration(),
                settings.catalog().reference(),
                settings.runtime().httpTimeout(),
                catalog.manifest().getProperty("camel.build"));
        registration.register();
        checkCancellation();
    }

    private void startCamel() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(dependencies.classLoader());
            main = new Main();
            configureCamel();
            checkCancellation();
            main.start();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void configureCamel() throws Exception {
        var configuration = CatalogLoader.properties(
                CatalogLoader.contained(catalog.root(), catalog.manifest().getProperty("configuration")));
        for (String key : configuration.stringPropertyNames()) {
            if (!key.startsWith("action.")) {
                throw new IllegalArgumentException("Catalog configuration must contain action properties only");
            }
            main.addProperty(key, configuration.getProperty(key));
        }
        main.configure().withRoutesIncludePattern(catalog.main().toUri().toString());
        main.addProperty(
                "camel.component.kamelet.location", catalog.kamelets().toUri().toString());
        main.addProperty("camel.server.enabled", "true");
        main.addProperty(
                "camel.server.port", Integer.toString(settings.runtime().port()));
        main.addProperty("camel.server.host", settings.runtime().bind());
        main.addProperty("camel.server.mcp-enabled", "true");
        main.addProperty("camel.server.mcp-tags", catalog.manifest().getProperty("tool.tags", "wsr-semantic-router"));
        main.addProperty("camel.server.mcp-server-name", settings.registration().forwardName());
        String guard = catalog.manifest().getProperty("guard.expert.bean");
        if (guard != null) {
            settings.deployment().setProperty("wsr.semantic-route.guard-bean", guard);
        }
        Experts.configure(main, settings.deployment(), catalog.manifest().getProperty("expert.bean"));
        main.getCamelContext().setApplicationContextClassLoader(dependencies.classLoader());
    }

    private void checkCancellation() throws InterruptedException {
        if (stopping.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Runtime startup cancelled");
        }
    }

    public Map<String, Object> status() {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("state", state);
        values.put("revision", settings.catalog().reference().revision());
        values.put("digest", settings.catalog().reference().digest());
        values.put(
                "camelVersion",
                main == null || main.getCamelContext() == null
                        ? CatalogLoader.CAMEL_VERSION
                        : main.getCamelContext().getVersion());
        if (catalog != null && catalog.manifest().getProperty("camel.build") != null) {
            values.put("catalogCamelBuild", catalog.manifest().getProperty("camel.build"));
        }
        values.put("forwardName", settings.registration().forwardName());
        if (failure != null) {
            values.put("failure", failure);
        }
        return values;
    }

    private void closeResources() throws Exception {
        // Cancellation can leave the startup thread interrupted. Ownership checks and
        // resource shutdown must run before that interrupt is restored.
        boolean interrupted = Thread.interrupted();
        try {
            Exception failure = null;
            try {
                if (registration != null) {
                    registration.close();
                }
            } catch (Exception e) {
                failure = e;
            }
            try {
                if (main != null) {
                    main.stop();
                }
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                if (dependencies != null) {
                    dependencies.close();
                }
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                if (catalog != null) {
                    CatalogLoader.delete(catalog.root());
                }
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() throws Exception {
        // Interrupt before waiting for the startup lock. The startup owner unwinds
        // its current operation and releases any partially acquired resources.
        if (stopping.compareAndSet(false, true)) {
            Thread owner = startupThread;
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
        }
        lifecycle.lock();
        try {
            state = "stopping";
            closeResources();
            state = "stopped";
        } finally {
            lifecycle.unlock();
        }
    }
}
