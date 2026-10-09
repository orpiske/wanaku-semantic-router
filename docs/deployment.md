# Deployment

WSR runs under its service authority. Its configured backend credentials determine action access. Caller attribution does not grant delegated authority. Place the MCP endpoint on a private network that Wanaku can reach. Place the preview endpoint on a private network that Barn can reach.

## Start a runtime

1. Publish the route in Barn.
2. Set `TYPESAFE_API_KEY` in the WSR process environment.
3. Start WSR with the route name.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime \
  --semantic-route support-route
```

This local command uses Barn at `http://localhost:8180`, Wanaku management at `http://localhost:8080`, and MCP at `http://localhost:8090/mcp`. It registers the forward as `support-route` in namespace `default`. Set `TYPESAFE_MODEL` to select a model. Without it, WSR uses the Camel TypeSafe AI component's default model.

Use different service addresses with these options:

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime \
  --semantic-route support-route \
  --barn-url http://barn:8180 \
  --registration-url http://wanaku:8080 \
  --bind 0.0.0.0 --mcp-address http://wsr:8090/mcp
```

Barn resolves the current published revision. WSR reads that selection once at startup. It downloads the archive and verifies its digest, revision, and runtime contract. A later publication does not change a running WSR. Restart WSR to select that publication. Draft edits do not change the selected publication. An explicit `--catalog-revision` selects a stored revision. An explicit digest or catalog pin must match the resolved publication.

The route name must identify one saved definition. An ambiguous name returns an error. A route must have a published revision. Older definitions with one publication can use that publication. Older definitions with several publications and no current selection need an explicit revision or a successful publish operation.

The default expert comes from the published expert snapshot. WSR supplies its packaged TypeSafe AI adapter when that snapshot selects `camel-typesafe-ai`. WSR uses environment references for the API key and optional model. A custom expert needs explicit `--expert` configuration. File settings and component properties override provider defaults. Barn stores no provider credential values.

### Environment settings

Use environment settings for Kubernetes and OpenShift. CLI options override these settings. Generic `--property` values also override them. Environment settings override an optional properties file.

| Environment variable | Setting |
| --- | --- |
| `WSR_SEMANTIC_ROUTE` | Published semantic route name |
| `WSR_BARN_URL` | Barn management URL |
| `WSR_REGISTRATION_URL` | Wanaku management URL |
| `WSR_MCP_ADDRESS` | MCP address that Wanaku can reach |
| `WSR_BIND` | MCP listener address |
| `WSR_MCP_PORT` | MCP listener port |
| `WSR_NAME` | Wanaku forward name |
| `WSR_NAMESPACE` | Wanaku namespace |
| `WSR_DATA_DIR` | Initialization and catalog storage directory |
| `WSR_WORK_DIRECTORY` | Catalog storage directory only |
| `WSR_CATALOG_REVISION` | Optional fixed publication revision |
| `WSR_CATALOG_SHA256` | Optional publication digest constraint |
| `WSR_CATALOG_NAME` | Optional catalog name constraint |
| `WSR_CATALOG_SERVICE` | Optional catalog service constraint |
| `WSR_CATALOG_MAIN` | Optional main file constraint |
| `WSR_STATUS_BIND` | Status listener address |
| `WSR_STATUS_PORT` | Status listener port |
| `WSR_HTTP_TIMEOUT_MS` | Complete HTTP operation timeout |
| `WSR_BARN_TOKEN_ENV` | Name of the Barn token environment variable |
| `WSR_WANAKU_TOKEN_ENV` | Name of the Wanaku token environment variable |

Use `WSR_MCP_ADDRESS` with a listener that binds to a non-loopback address. WSR derives a local MCP address only for a loopback listener. The token settings contain environment variable names. Put the token values in separate Secret-backed environment variables.

### Explicit catalog pins

Use catalog options when you already have the publication metadata. This mode retains the existing explicit configuration contract.

1. Publish a definition in Barn.
2. Copy the catalog name, revision, and SHA-256 digest from the publication result.
3. Set the Barn URL and Wanaku management URL.
4. Set the advertised MCP address to an address that Wanaku can reach.
5. Configure the expert bean and provider settings.
6. Supply credential values through environment variables.
7. Start WSR with the publication values.

Replace the catalog name, revision, and digest in this example. Set `TYPESAFE_API_KEY` and `TYPESAFE_MODEL` in the process environment before startup.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime \
  --barn-url http://barn:8080 \
  --service-catalog semantic-REPLACE-ID-1 \
  --service-catalog-system service \
  --catalog-revision 1 \
  --catalog-sha256 REPLACE-WITH-PUBLICATION-SHA256 \
  --registration-url http://wanaku:8080 \
  --name semantic-support --namespace default \
  --mcp-address http://wsr:8090/mcp \
  --bind 0.0.0.0 --mcp-port 8090 \
  --data-dir /var/lib/wsr \
  --expert supportExpert=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter \
  --property 'camel.component.typesafe-ai.api-key={{env:TYPESAFE_API_KEY}}' \
  --property 'camel.component.typesafe-ai.model={{env:TYPESAFE_MODEL}}'
```

WSR downloads the selected catalog ZIP from Barn's data store. It verifies the archive before it starts Camel. Select a location that the process can write. WSR creates the data directory and a private extraction directory inside it. Shutdown removes that extraction directory.

The CLI uses CIC option names where the settings match. Runtime options map to these deployment properties:

| Option | Property | Default |
| --- | --- | --- |
| `--semantic-route` | `wsr.semantic-route` | Required for route-name mode |
| `--barn-url` | `wsr.barn.url` | Local default in route-name mode; required with explicit catalog mode |
| `--registration-url`, `--wanaku-url` | `wsr.wanaku.url` | Local default in route-name mode; required with explicit catalog mode |
| `--service-catalog`, `--catalog-name` | `wsr.catalog.name` | Resolved from the route; required with explicit catalog mode |
| `--service-catalog-system`, `--catalog-service` | `wsr.catalog.service` | `service` |
| `--catalog-revision` | `wsr.catalog.revision` | Current published revision; required with explicit catalog mode |
| `--catalog-sha256` | `wsr.catalog.sha256` | Resolved from the route; required with explicit catalog mode |
| `--catalog-main` | `wsr.catalog.main` | Catalog index selection |
| `--name`, `--forward-name` | `wsr.forward.name` | Route name, or published tool name if the route name is not a valid identifier; required with explicit catalog mode |
| `--namespace` | `wsr.forward.namespace` | `default` |
| `--mcp-address` | `wsr.mcp.address` | Loopback listener address in route-name mode; required for a non-loopback listener or explicit catalog mode |
| `--mcp-port`, `--port` | `wsr.port` | `8090` |
| `--bind` | `wsr.bind` | `127.0.0.1` |
| `--status-port` | `wsr.status.port` | `8091` |
| `--status-bind` | `wsr.status.bind` | `127.0.0.1` |
| `--http-timeout-ms` | `wsr.http.timeout-ms` | `30000` |
| `--work-directory` | `wsr.work-directory` | Java temporary directory |
| `--barn-token-env` | `wsr.barn.token-env` | No token |
| `--wanaku-token-env` | `wsr.wanaku.token-env` | No token |

`--barn-token-env` and `--wanaku-token-env` take environment variable names. They do not take token values. Use `--expert NAME=CLASS` to enable each expert. Its name must match the definition's expert bean name. Named expert options replace the enabled expert list from a file or generic property. Use `--property KEY=VALUE` (`-p`) for provider settings or other deployment properties. The value can contain `=`. Quote Camel environment references to preserve their characters.

An explicit `--data-dir` also selects runtime catalog storage. An explicit `--work-directory` overrides that storage selection. Without either option, WSR uses `wsr.work-directory` from the configuration or the Java temporary directory.

### Optional properties file

Existing deployment files remain supported. Use one positional file or `--config FILE`. Do not supply both forms. File settings apply first. Environment settings override them. Repeatable `--property` values override both sources. Named options override all earlier sources. Argument order does not change this precedence. The last value wins for a repeated property key. Supply each named scalar option once. Repeated scalar options return a usage error.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime \
  --config /etc/wsr/runtime.properties --mcp-port 8090
```

The example files are `config/runtime.properties.example` and `config/preview.properties.example`.

`wsr.catalog.service` is the index service key. Barn's generated catalogs use `service`. `wsr.catalog.main` is optional. When set, it must match the selected index main file. The runtime accepts no `latest` revision. Do not change a running instance's catalog files.

`wsr.http.timeout-ms` limits each complete Barn or Wanaku HTTP operation. The limit includes response-body receipt. The default is 30000 ms. A timed-out operation cancels its response transfer. MCP initialization and discovery have 10-second limits. The complete readiness check has a 20-second limit. Its cleanup wait has a 5-second limit. Failed startup stops Camel and closes its MCP server. Barn and Wanaku HTTP response bodies have a 48 MiB size limit. Route resolution metadata has a 1 MiB size limit.

The status endpoint defaults to `http://127.0.0.1:8091/api/v1/status`. Its response contains `state`, `revision`, `digest`, `camelVersion`, and `forwardName`. It includes `catalogCamelBuild` when the catalog supplies build metadata. This field describes the publication, not the runtime binaries. A startup failure writes the same fields and the failure type to standard error. It exits with a failure status. WSR checks its instance label before it removes an attempted registration. This check also applies when Wanaku accepts the POST but its response is lost or invalid.

Send SIGTERM to stop WSR. During startup, shutdown requests cancellation and releases acquired resources before shutdown completes. A stopped runtime cannot start again. It removes its own forward before it stops Camel. A replacement process has a different instance label. Shutdown leaves that replacement's forward registered. If Wanaku is unreachable during shutdown, the process reports the cleanup failure. Wanaku's normal reconnect behavior then observes the stopped upstream.

## Kafka destinations

WSR includes the `camel-kafka` component. Upload a native Kafka sink Kamelet in Barn. Select it as a semantic destination. Set its `topic` and `bootstrapServers` configuration values. Use broker addresses that the WSR process can reach. Publish the semantic route and start WSR with its route name.

WSR sends the original message to the selected Kafka topic. It returns a text acknowledgement after the sink completes. A Kafka send failure returns a tool error.

Use `env:KAFKA_PASSWORD` for a password parameter in Barn. Set `KAFKA_PASSWORD` in the WSR process environment. Use a Secret-backed environment variable in Kubernetes or OpenShift. Keep the Kamelet's password parameter marked as a secret in its configuration schema. Set the Kafka security protocol and authentication mechanism required by your broker.

The Kafka dependency uses the packaged Camel version. Other Camel components must also be included in the WSR distribution before a catalog can declare them. WSR does not download another Camel version at startup.

## Start semantic evaluation preview

Configure the same expert bean and provider settings as the runtime. Start the semantic evaluation service.

```sh
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar preview \
  --bind 0.0.0.0 --port 8092 \
  --preview-timeout-ms 10000 --max-concurrent 4 \
  --preview-token-env WSR_PREVIEW_TOKEN \
  --expert supportExpert=org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter \
  --property 'camel.component.typesafe-ai.api-key={{env:TYPESAFE_API_KEY}}' \
  --property 'camel.component.typesafe-ai.model={{env:TYPESAFE_MODEL}}'
```

Set `WSR_PREVIEW_TOKEN`, `TYPESAFE_API_KEY`, and `TYPESAFE_MODEL` in the process environment before startup. Set Barn's preview URL to `http://wsr-preview:8092/api/v1/preview`. Configure the matching token reference in Barn. The service creates an isolated Camel context per request without action routes. Barn must send `expertBean`, `operation`, optional `parameters` (default `{}`), and `state`; read `resultType`, typed `value`, and `diagnostics` from the response. See [the preview contract](artifact-contract.md#preview-boundary). Migrate old preview clients and republish catalogs using `semantic.evaluation` before deploying this runtime; it does not translate old question YAML.

| Preview option | Property | Default |
| --- | --- | --- |
| `--bind` | `wsr.preview.bind` | `127.0.0.1` |
| `--port`, `--preview-port` | `wsr.preview.port` | `8092` |
| `--preview-timeout-ms` | `wsr.preview.timeout-ms` | `10000` |
| `--max-concurrent` | `wsr.preview.max-concurrent` | `4` |
| `--preview-token-env` | `wsr.preview.token-env` | No token |

## Kubernetes and OpenShift

Use [the deployment example](../config/kubernetes.yaml.example) to supply the route name and service addresses through environment settings. The container needs only the `runtime` command. Provider credentials use Secret references. The example needs no properties file or ConfigMap. Replace its image and route name. Use an image that your cluster can pull. Create the referenced `wsr-provider` Secret with the key `api-key`. Its `model` key is optional. Apply the Deployment and Service in the namespace that contains the referenced Barn and Wanaku services.

The Service address `http://wsr:8090/mcp` must be reachable from Wanaku. Set `--status-bind 0.0.0.0` when the cluster must read runtime status. Use separate Deployments for runtime and preview. Add the preview token to its Secret and configure Barn with the matching reference.

The example keeps catalog storage in the container's writable temporary directory. A restarted process downloads and verifies its pinned publication again. The image supports the UID assigned by OpenShift. The example does not set a fixed UID or group. If you add a volume, configure its permissions for the UID and groups assigned by your cluster.

## Container

Build the container after `mvn verify` succeeds. The Dockerfile copies the packaged JAR and `target/lib/`. It does not run a second Maven build.

```sh
podman build -t wanaku-semantic-router:local .
podman run --rm --network wanaku-private \
  --env TYPESAFE_API_KEY --env TYPESAFE_MODEL \
  --env WSR_SEMANTIC_ROUTE=support-route \
  --env WSR_BARN_URL=http://barn:8180 \
  --env WSR_REGISTRATION_URL=http://wanaku:8080 \
  --env WSR_MCP_ADDRESS=http://wsr:8090/mcp \
  --env WSR_BIND=0.0.0.0 \
  wanaku-semantic-router:local runtime
```

Set `--bind 0.0.0.0` for container network access. Set the advertised MCP address to the container service name. Use separate processes for runtime and preview. Pin the built image digest for deployment. Preserve the complete JAR distribution to retain the resolved snapshot artifacts.

The image creates the `wsr` user with UID 10001 and root group membership. Its writable directories grant the same access to that group. OpenShift can assign an arbitrary UID with root group membership. Its home is `/home/wsr`. Packaged libraries are readable by that user. Java temporary files and default catalog extraction use `/home/wsr/tmp`. Dependency resolution uses a private repository inside the extracted catalog directory. It does not write to `/root/.m2`.

A custom `--work-directory` must be writable by the process UID or its assigned groups.

The packaged SDK Maven downloader resolves external non-Camel dependencies from Maven Central. It does not require the Maven CLI. It does not read `settings.xml`. WSR waits up to two minutes for resolution. On timeout, it interrupts the worker and closes the downloader. Cancellation follows SDK resolver behavior. See [SDK reuse](sdk-reuse.md) for repository and cleanup limits.

Run the container checks after the image build:

```sh
./scripts/container-smoke.sh wanaku-semantic-router:local
```

The checks verify the default runtime user and an arbitrary UID. They check writable directories and readable libraries. They run CLI help and version commands from the packaged image. They resolve one fixed-release Maven dependency through the packaged SDK downloader as the arbitrary UID. They require no inference provider or backend services.

Local container checks require a running Podman backend. Use the `Multiplatform PR Builds` workflow when local execution is unavailable. Verify both architecture results before deploying the image.

### Native architecture builds

The CI build follows the Camel Integration Capability container convention. Each native runner builds and verifies Maven artifacts before it builds the image with Podman. `ubuntu-latest` builds `x86_64`. `ubuntu-24.04-arm` builds `aarch64`. Pull request builds run the container checks on both runners. Pull request builds do not log in to Quay or publish images.

The `Build Main` workflow publishes images from `main` and `v*` tags in `wanaku-ai/wanaku-semantic-router`. It uses the `QUAY_USERNAME` and `QUAY_PASSWORD` repository secrets. The registry account must have write access to `quay.io/wanaku/wanaku-semantic-router`.

The workflow publishes an image for each architecture:

| Git reference | x86_64 tag | aarch64 tag | Combined manifest |
| --- | --- | --- | --- |
| `main` | `main-x86_64` | `main-aarch64` | `latest` |
| `v0.1.0` | `v0.1.0-x86_64` | `v0.1.0-aarch64` | `v0.1.0` |

`images.txt` lists the combined image name. `build-manifests.sh` creates and pushes the combined manifest after both native image pushes succeed. The manifest lets the container engine select the host architecture. Use a combined manifest digest when deployment must retain both architectures. Use an architecture image digest when deployment uses one architecture.
