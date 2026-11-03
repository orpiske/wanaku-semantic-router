package org.wanaku.wsr;

import java.io.IOException;

/** A startup failure whose message is WSR-owned text only, so the CLI and the status can show it. */
final class StartupFailure extends IOException {
    StartupFailure(String message) {
        super(message);
    }

    StartupFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /** The message of the first StartupFailure in the cause chain, or null. */
    static String diagnostic(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StartupFailure startup) {
                return startup.getMessage();
            }
        }
        return null;
    }
}
