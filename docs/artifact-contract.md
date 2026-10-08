# Artifact and execution contract

Barn publishes an existing service catalog ZIP. The archive root contains `index.properties`. The selected service contains one main YAML file, auxiliary Kamelets, deployment parameter references, and a dependency list.

## Catalog metadata

Use these index fields:

```properties
catalog.name=semantic-<definition-id>-<revision>
catalog.services=service
catalog.routes.service=service/router.camel.yaml
catalog.dependencies.service=service/dependencies.txt
catalog.properties.service=service/service.properties
```

The main file directory contains `semantic-router.properties`:

```properties
contract.version=1
catalog.revision=<revision>
camel.version=4.23.0-SNAPSHOT
camel.build=20261006.103638
main=service/router.camel.yaml
kamelets=service/kamelets/wsr-billing-action.kamelet.yaml,service/kamelets/wsr-technical-action.kamelet.yaml
dependencies=service/dependencies.txt
configuration=service/service.properties
input.profile=message-to-string/v1
expert.bean=supportExpert
tool.name=route_support
tool.tags=wsr-semantic-router
question=department
preview.main=service/preview.camel.yaml
```

Kamelet file references must share one directory. WSR checks that each resource exists before startup. `configuration` must match the selected index properties reference. Catalog configuration contains `action.*` keys only. Credential references use Camel environment placeholders. Credentials and provider settings belong to the external deployment file.

`camel.version` defines runtime compatibility. `camel.build` is optional catalog publication metadata. WSR accepts existing Barn publications that contain this field. It does not require a specific build timestamp or use this field to identify the runtime binaries.

The selected publication supplies `catalogName`, `revision`, and `sha256` to the operator. `wsr.catalog.name` selects the Barn download. `wsr.catalog.service` selects the index service. Publication does not activate a runtime. WSR reports the loaded revision after readiness and registration succeed.

The initial compatibility profile is `message-to-string/v1`. Each action receives a string body and returns a string result. Barn rejects incompatible combinations. Catalog authors can supply a Kamelet adapter for other operations. The wizard does not infer tool arguments from the selected label.

## Dependencies and experts

The dependency file accepts `org.apache.camel:camel-component`, `camel:component`, `mvn:groupId:artifactId:version`, or a fixed release GAV. The distribution supplies the Camel components. The native `camel:core` shorthand uses the packaged `camel-core-engine`. An explicit Camel version must equal `4.23.0-SNAPSHOT`. A missing Camel component fails startup. External expert dependencies require a fixed release GAV. The SDK Maven downloader resolves their runtime dependencies before Camel starts. External dependencies cannot replace packaged Camel libraries. The packaged SDK resolver does not require the Maven CLI at runtime.

A GAV identifies an implementation dependency. `expert.bean` identifies a configured Camel registry bean. The deployment selects enabled expert beans and their implementation classes. Implementations must implement the native `SemanticAdapter` interface. Camel owns provider transport and semantic evaluation. WSR does not implement a separate evaluation engine.

## Verified Camel API

The semantic artifact is `org.apache.camel:camel-semantic:4.23.0-SNAPSHOT`. The TypeSafe AI artifact is `org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT`. The Camel BOM manages these versions. The native YAML declaration uses `semantic.question`, `type: choice`, `expert`, `state`, `instructions`, and a criteria map. The route evaluates `ref:department` once and stores its decision. Ordinary Camel choice predicates select fixed Kamelet endpoints.

[CAMEL-25382](https://issues.apache.org/jira/browse/CAMEL-25382) remained In Progress and unresolved when verified with the official Jira REST API on 2026-10-07. The tested source already supports named expert selection and static capability declarations. This implementation uses those verified APIs. It does not require the proposal's illustrative custom evaluation syntax. The [native semantic documentation](https://camel.apache.org/components/next/languages/semantic-language.html) describes the existing named-question execution model.

The public route uses native `ai-tool:` metadata. WSR sets the exact `tool.tags` value as Camel's MCP exposure filter. Helper routes remain internal. Readiness requires successful MCP initialization and discovery of exactly the declared tool.

## Preview boundary

The preview service accepts:

```json
{
  "input": "message",
  "instructions": "Select the support action.",
  "criteria": {"billing": "Invoices", "technical": "Technical problems", "no_match": "Neither action"},
  "expertBean": "supportExpert",
  "message": "I have a question about an invoice."
}
```

It creates a native `SemanticQuestion` with the same fields as the generated YAML. Its state selector is `${body}`. The isolated context has no routes. It evaluates the native expression directly. It never accepts executable YAML or calls action dispatch.

A success returns `{"label":"billing","diagnostics":{}}`. Available native confidence or choice probabilities appear in `diagnostics`. WSR does not invent diagnostics. Invalid input returns HTTP 400. Capacity exhaustion returns HTTP 429. Provider or malformed evaluation failures return HTTP 502 with `evaluation_failed`. Evaluation timeout returns HTTP 504 with `evaluation_timeout`.
