# Wanaku Semantic Router

Wanaku Semantic Router (WSR) runs a Barn catalog as an Apache Camel application. Barn authors and publishes the integration. Camel evaluates its semantic evaluations and executes fixed Kamelet branches. Wanaku discovers and governs the public MCP tool.

This repository implements [Barn #182](https://github.com/wanaku-ai/wanaku-barn/issues/182) and the native runtime checks for [Barn #180](https://github.com/wanaku-ai/wanaku-barn/issues/180). [Wanaku #2101](https://github.com/wanaku-ai/wanaku/issues/2101) tracks the complete workstream.

## Build and package

Use Java 21 or later and Maven 3.9 or later.

Check out `wanaku-capabilities-java-sdk` beside WSR as `../sdk`. Use the SDK commit listed in `.github/sdk-revision`. Install the SDK before you build WSR. CI installs that same commit on both native architectures.

```sh
mvn -B -ntp -f ../sdk/pom.xml clean install
mvn -B -ntp clean verify
```

The build creates `target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar` and `target/lib/`. Keep the JAR and the library directory together. The JAR manifest contains the library paths. The tests use a local HTTP provider fixture. They do not need paid inference or backend services.

The runtime uses Camel `4.23.0-SNAPSHOT` artifacts containing [PR #27494](https://github.com/apache/camel/pull/27494). Republish catalogs using `semantic.evaluation`; old `semantic.question` YAML is incompatible. Migrate Barn preview clients to the new request and response contract before deployment. The Camel BOM manages all Camel dependency versions. Maven resolves the current snapshot artifacts during a build. A later build can resolve different binaries. Preserve the complete packaged distribution or container image digest to reproduce a deployment.

See [the contract](docs/artifact-contract.md) for the verified native APIs. See [deployment](docs/deployment.md) for runtime and preview setup. See [SDK reuse](docs/sdk-reuse.md) for shared catalog transport, extraction, and dependency resolution.

The build applies the CIC Spotless rules during compilation. It uses Palantir Java Format and explicit imports. Use braces for control blocks. Apply formatting before you submit changes. Check committed formatting before you build a release.

```sh
mvn -B -ntp spotless:apply
mvn -B -ntp spotless:check
```

## Command line

Start a published semantic route by its Barn name. Set `TYPESAFE_API_KEY` in the process environment for the default TypeSafe AI expert. A deployment properties file is optional. Use [deployment](docs/deployment.md) for environment settings and a Kubernetes/OpenShift example.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar --help
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar --version
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route support-route
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --help
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar preview --help
```

Local route startup defaults to Barn at `http://localhost:8180` and Wanaku at `http://localhost:8080`. WSR resolves the current published catalog once. It verifies the fixed revision and digest before startup. It keeps that publication until restart. Kubernetes can set `WSR_SEMANTIC_ROUTE`, `WSR_BARN_URL`, `WSR_REGISTRATION_URL`, and `WSR_MCP_ADDRESS`.

Use `--expert NAME=CLASS` to configure a custom expert bean. Use `--property KEY=VALUE` (`-p`) for provider and Camel component settings. Repeat these options for additional settings. Use environment references for credentials. Settings apply in this order: file, environment, generic properties, and named options. A later source overrides an earlier source.

Each command accepts `--help` (`-h`) and `--version` (`-V`). These options do not start a service or read deployment settings. The version output contains the packaged WSR version and the Camel version. An unpackaged build uses `development` for the WSR version.

The CLI returns `0` for help and version output. It returns `2` for invalid arguments or invalid deployment settings. It returns `1` for startup or execution failures. Configuration validation errors omit supplied values. Operational errors report the failure type and runtime status without provider error messages. Stop a running service with `SIGTERM` or `Ctrl+C`. The shutdown hook closes the service and removes the runtime forward when that process owns it.

Use `--init-from` to initialize deployment files from Git before service startup. Use `--data-dir` to select runtime catalog storage and the initialization directory. WSR uses the SDK initializer and retry policy. See [initialization](docs/initialization.md) for source, cache, and timeout behavior. Runtime routes continue to come from the selected Barn publication.

## Runtime behavior

One process loads one selected service from one fixed catalog revision. It downloads the Barn `DataStore` ZIP through the existing catalog endpoint. It checks the SHA-256 digest, revision, Camel version, main file, and auxiliary resources. It loads action settings and named expert beans before Camel starts.

WSR uses the maintained LangChain4j MCP client for initialization and tool discovery before it registers the Wanaku forward. A startup failure stops Camel and removes extracted files. A registered forward contains the active revision and digest as labels. It also records the catalog's optional Camel build metadata. This metadata describes catalog publication, not the runtime binaries. Shutdown removes the forward only when its instance label still belongs to that process. Updates require restart or replacement with explicit publication pins.

The MCP tool accepts `message`, a required string. A selected branch returns the action result. `no_match` returns the configured no-match text. Provider failures, malformed answers, and action failures return MCP tool errors. Evaluation output selects a fixed label. It cannot supply a destination URI.

## Semantic evaluation preview

The `preview` process exposes `POST /api/v1/preview`. Supply an enabled `expertBean`, an `operation`, optional expert-owned `parameters`, and `state`. It creates a separate Camel context and evaluates the native `SemanticEvaluation` contract without action routes or Kamelets. It returns `resultType`, a typed `value` (boolean, choice string, score number, or classification labels), and only the diagnostics supplied by the expert. See [the preview contract](docs/artifact-contract.md#preview-boundary) for examples and errors.

The service limits concurrent evaluations and request size. It applies an evaluation timeout. A cancelled evaluation retains its capacity slot until the worker exits. Deployment configuration selects expert implementations and credentials. Barn receives no credential values.

## Validation scope

The reference checks cover native semantic selection, auxiliary Kamelet loading, MCP discovery and invocation, explicit no-match, provider failure, malformed results, archive safety, digest checks, runtime compatibility, fetch failure, failed Camel startup, failed registration cleanup, accepted POST response loss, full HTTP completion deadlines, action failure, preview timeout and capacity, registration ordering, shutdown, and a packaged JAR smoke test. Deterministic fixtures check integration behavior. They do not measure classification quality.

For model evaluation, use a separate deployment and a representative set of saved examples. Run semantic evaluation previews first. Compare each expected label with its actual label. Review no-match and error cases separately. Use demonstration actions before enabling operations with backend side effects. Record the provider, model, revision, and distribution or image digest with the results.
