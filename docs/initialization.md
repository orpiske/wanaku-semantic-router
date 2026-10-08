# Initialization

WSR follows the Camel Integration Capability startup sequence. It initializes deployment resources before it reads deployment settings. It then loads the selected Barn publication, starts Camel, checks MCP readiness, and registers the forward.

## Git deployment files

Supply `--init-from` on the `runtime` or `preview` command. Set `--data-dir` to a directory that the service user can write.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --init-from https://github.com/example/wsr-deployment.git --data-dir /var/lib/wsr support/runtime.properties
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar preview --init-from https://github.com/example/wsr-deployment.git --data-dir /var/lib/wsr support/preview.properties
```

WSR clones into `DATA_DIR/cloned-repo`. A relative deployment path resolves inside that repository. The path must remain inside the repository after symbolic links are resolved. An absolute deployment path can refer to a separate file.

WSR accepts HTTPS, SSH, and local file Git sources. Use configured SSH keys for private repositories. Do not put credential values in the source URL. WSR rejects embedded URL passwords, HTTPS user information, query strings, and fragments. Keep provider and backend credentials in environment variables.

The first initialization clones the repository's default branch. A later startup reuses the existing checkout. It verifies that the checkout belongs to the configured source. It does not pull updates. Use a new data directory to select a fresh checkout. Keep deployment changes under version control.

Without `--init-from`, WSR reads an optional deployment file directly. You can use `--init-from` without a deployment file. WSR initializes the checkout and then uses the command options. Help and version output do not initialize a repository or read deployment settings.

`WSR_DATA_DIR` selects the initialization and catalog storage directory. An explicit `--data-dir` overrides that environment setting and catalog storage from a file or generic property. `WSR_WORK_DIRECTORY` selects a separate catalog storage directory. An explicit `--work-directory` selects a separate catalog storage directory. See [deployment](deployment.md) for command options and their precedence.

## Limits and retries

| Option | Default | Behavior |
| --- | --- | --- |
| `--data-dir` | `user.home/.wanaku/wsr` | Stores the initialized checkout. An explicit option also selects runtime catalog storage. |
| `--retries` | `3` | Allows up to three additional clone attempts. The maximum is 12. |
| `--wait-seconds` | `5` | Sets the initial retry delay. The maximum is 30 seconds. |
| `--init-timeout-seconds` | `120` | Limits the complete initialization operation. The maximum is 600 seconds. |

WSR uses the SDK exponential backoff policy. Retry delays have a 30-second limit. The initialization deadline includes clone attempts and retry delays. WSR starts the SDK initializer in a separate JVM. On timeout or interruption, it stops the helper and its transport processes. It waits for their exit before it removes staging files.

A completed clone moves into the final checkout directory. A failed initialization leaves no new final checkout. WSR preserves an existing conflicting directory and reports failure. A runtime initialization failure occurs before catalog download or forward registration.

## Runtime boundary

Initialization supplies deployment files. The runtime still verifies and loads its executable routes from the pinned Barn archive. Route-name startup resolves the fixed publication from Barn before this verification. It does not execute YAML files from the initialized repository. The preview service loads no action routes.

WSR reuses the released SDK initializer, retry, catalog model, and JSON serializer APIs. It excludes the SDK runtime module's transitive dependencies. It supplies JGit and the SDK common module explicitly. The WSR Camel BOM manages Camel dependency versions. See [SDK reuse](sdk-reuse.md) for the dependency boundary.
