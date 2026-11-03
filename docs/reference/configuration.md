---
title: Runtime configuration
description: Environment settings, command options, deployment properties, timeouts, limits, and the status endpoint of the Wanaku Semantic Router runtime.
---

# Runtime configuration

This page lists the environment settings, command options, and deployment properties of the WSR runtime, with their precedence, limits, and the status endpoint.

## Environment settings

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
| `WSR_AUDIT_ENABLED` | Capture and forward runtime audit records; default `true` |
| `WSR_AUDIT_URL` | Optional complete audit ingestion URL |
| `WSR_BARN_TOKEN_ENV` | Name of the Barn token environment variable |
| `WSR_WANAKU_TOKEN_ENV` | Name of the Wanaku token environment variable |

Use `WSR_MCP_ADDRESS` with a listener that binds to a non-loopback address. WSR derives a local MCP address only for a loopback listener. The token settings contain environment variable names. Put the token values in separate Secret-backed environment variables.

## Runtime options

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

## Optional properties file

Existing deployment files remain supported. Use one positional file or `--config FILE`. Do not supply both forms. File settings apply first. Environment settings override them. Repeatable `--property` values override both sources. Named options override all earlier sources. Argument order does not change this precedence. The last value wins for a repeated property key. Supply each named scalar option once. Repeated scalar options return a usage error.

```sh
java -jar target/wanaku-semantic-router-0.3.0-SNAPSHOT.jar runtime \
  --config /etc/wsr/runtime.properties --mcp-port 8090
```

The example files are `config/runtime.properties.example` and `config/preview.properties.example`.

`wsr.catalog.service` is the index service key. Barn's generated catalogs use `service`. `wsr.catalog.main` is optional. When set, it must match the selected index main file. The runtime accepts no `latest` revision. Do not change a running instance's catalog files.

## Timeouts and limits

`wsr.http.timeout-ms` limits each complete Barn or Wanaku HTTP operation. The limit includes response-body receipt. The default is 30000 ms. A timed-out operation cancels its response transfer. MCP initialization and discovery have 10-second limits. The complete readiness check has a 20-second limit. Its cleanup wait has a 5-second limit. Failed startup stops Camel and closes its MCP server. Barn and Wanaku HTTP response bodies have a 48 MiB size limit. Route resolution metadata has a 1 MiB size limit.

## Status endpoint

The status endpoint defaults to `http://127.0.0.1:8091/api/v1/status`. Its response contains `state`, `revision`, `digest`, `camelVersion`, and `forwardName`. It includes `catalogCamelBuild` when the catalog supplies build metadata. This field describes the publication, not the runtime binaries. A startup failure writes the same fields and the failure to standard error. For the MCP self-check and the forward registration, `failure` names the failed step, for example `Wanaku forward registration failed (HTTP 409)`. For other failures, it holds the exception type only. It also writes them to `/dev/termination-log` when that file is writable. It exits with a failure status. The status listener also serves the [health probes](../operate/kubernetes.md#health-probes). WSR checks its instance label before it removes an attempted registration. This check also applies when Wanaku accepts the POST but its response is lost or invalid.

## Logging

WSR uses SLF4J with Log4j2 as the logging backend. The JAR contains a default `log4j2.properties` file. This file writes to the console. It sets `org.apache.camel` and the root logger to `INFO`. It sets `ai.wanaku` and `org.wanaku.wsr` to `DEBUG`.

To use a different configuration, set the `log4j.configurationFile` system property:

```shell
java -Dlog4j.configurationFile=/path/to/log4j2.properties -jar wanaku-semantic-router.jar
```

In a container, mount the file and add the property to `JAVA_TOOL_OPTIONS`. The image sets `JAVA_TOOL_OPTIONS=-Duser.home=/home/wsr`. Keep this value when you add the property.

## Next steps

- [Run a published semantic route](../operate/run-a-published-route.md): start the runtime with these settings.
- [Run the semantic evaluation preview](../operate/preview-service.md): the options of the preview service.
