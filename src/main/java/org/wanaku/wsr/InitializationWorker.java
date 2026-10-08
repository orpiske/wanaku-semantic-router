package org.wanaku.wsr;

import java.nio.file.Path;
import org.eclipse.jgit.api.errors.GitAPIException;
import ai.wanaku.capabilities.sdk.runtime.camel.init.InitializerFactory;

/** Isolates the SDK's unbounded Git operation from the service process. */
public final class InitializationWorker {
    private InitializationWorker() {}

    public static void main(String[] args) {
        int status = 1;
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException("Invalid initialization arguments");
            }
            String source = Initialization.validatedSource(args[0]);
            InitializerFactory.createInitializer(source, Path.of(args[1])).initialize();
            status = 0;
        } catch (GitAPIException e) {
            status = 75;
        } catch (Exception e) {
            System.err.println("Initialization helper failed: " + e.getClass().getSimpleName());
        }
        System.exit(status);
    }
}
