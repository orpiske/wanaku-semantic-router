# Java SDK reuse

WSR uses the Wanaku Capabilities Java SDK for catalog requests, archive extraction, dependency resolution, Git initialization, retry policy, and JSON serialization.

WSR uses SDK `0.3.0-SNAPSHOT`. The HTTP extension is part of the companion SDK change. CI installs the SDK from the commit in `.github/sdk-revision` before it builds WSR. A local build must install that SDK revision first. See the [build instructions](../README.md).

| SDK feature | WSR use |
| --- | --- |
| `ServicesHttpClient` | Download the selected Barn service catalog. WSR supplies a bounded HTTP client and a request customizer for Bearer authentication and the configured timeout. |
| `DataStore` | Read the shared catalog payload contract. |
| `ServiceCatalogExtractor` | Extract the checked archive and select the service main file from the catalog index. |
| `GAV` and `WanakuMavenDownloader` | Parse coordinates and resolve fixed release dependencies with the embedded Maven resolver. |
| `InitializerFactory` | Initialize deployment files from Git before service startup. |
| `ExponentialBackoffRetryPolicy` | Retry initialization attempts with exponential delays. |
| `JacksonSerializer` | Serialize forward registration requests. |

## Catalog checks

The SDK catalog request accepts a bare object, a response envelope, or a single-object array. It ignores unknown catalog metadata. It rejects missing archive data and ambiguous arrays.

WSR limits HTTP response bodies to 48 MiB. Its HTTP deadline includes response-body receipt. The supplied transport cancels a timed-out transfer. HTTP failure messages omit response bodies.

WSR verifies the SHA-256 digest before extraction. The SDK has no shared digest helper. WSR uses the Java `MessageDigest` API and a constant-time digest comparison.

WSR checks archive paths, duplicate entries, expanded byte count, and entry count before it calls the SDK extractor. It validates the selected revision, profile, Camel version, main file, and auxiliary resources before it starts Camel. The SDK performs extraction and catalog index mapping.

## Dependency resolution

The distribution supplies Camel components. WSR checks Camel declarations against packaged components before it creates a downloader. External expert dependencies require fixed release coordinates. The SDK resolves their compile and runtime dependencies into a private repository inside the extracted catalog directory. WSR rejects downloaded Camel libraries before it exposes the SDK classloader.

Catalog normalization and GAV parsing stay in `Dependencies.readDeclarations`. WSR supplies a local Camel version default because its unreleased `4.23.0-SNAPSHOT` differs from the SDK's released default. Replace this default with `RuntimeVersionHelper.getVersions()` when the SDK supports the WSR Camel version. WSR compatibility checks and fixed release checks remain separate.

WSR owns the downloader and its classloader. Shutdown closes the downloader before it removes extracted files. Resolution has a two-minute wait limit. On timeout, WSR interrupts the worker and closes the SDK downloader. Cancellation and resolver cleanup follow SDK behavior. The resolver does not run in a separate process.

The current WSR configuration resolves from Maven Central. It does not read Maven `settings.xml`. Maven mirrors, private repository credentials, proxies, and active profiles are not supported through WSR configuration. Do not place credentials in catalog dependencies.

## Camel dependency boundary

WSR uses `capabilities-runtimes-camel-common` for initialization, retry, and archive extraction. These helpers do not need Camel runtime classes. WSR excludes the module's transitive dependencies. It supplies the used helpers' dependencies explicitly. The WSR Camel BOM manages all semantic and MCP components.

WSR retains its forward registration implementation. Registration records revision, digest, and instance labels. Shutdown checks instance ownership before it removes a forward. The SDK forward contract does not provide these labels.

See [initialization](initialization.md) for command options and startup behavior. Initialized deployment files do not bypass Barn artifact checks.
