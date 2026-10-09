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
evaluation=department
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

The semantic artifact is `org.apache.camel:camel-semantic:4.23.0-SNAPSHOT`. The TypeSafe AI artifact is `org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT`. The Camel BOM manages these versions. Use artifacts containing [Camel PR #27494](https://github.com/apache/camel/pull/27494), merged on 2026-10-08. Its native API replaces `SemanticQuestion`/`SemanticQuestions` with `SemanticEvaluation`/`SemanticEvaluations`.

Declare the routing evaluation as:

```yaml
- semantic:
    evaluation:
      department:
        operation: choice
        expert: supportExpert
        state: "${body}"
        parameters:
          instructions: Select the support action that can handle this request.
          criteria:
            billing: Invoices, payments, and refunds.
            technical: Bugs, outages, and technical problems.
            no_match: Requests that neither support action can handle.
```

The expert owns parameter names, defaults, and evaluation policy. The route evaluates `ref:department` once and stores its decision. Ordinary Camel choice predicates select fixed Kamelet endpoints. Runtime dispatch remains single-label.

Republish catalogs containing `semantic.question` using `semantic.evaluation`. Camel rejects the removed declaration syntax; WSR supplies no translation layer. Within an evaluation, `type: choice` and instruction-driven shorthand remain supported by Camel; the example uses explicit expert operations and parameters. Keep `contract.version=1`; rename the informational `question` metadata to `evaluation`. The retained contract version does not make old YAML compatible.

The public route uses native `ai-tool:` metadata. WSR sets the exact `tool.tags` value as Camel's MCP exposure filter. Helper routes remain internal. Readiness requires successful MCP initialization and discovery of exactly the declared tool.

## Preview boundary

`POST /api/v1/preview` accepts:

```json
{
  "expertBean": "supportExpert",
  "operation": "choice",
  "parameters": {
    "instructions": "Select the support action.",
    "criteria": {"billing": "Invoices", "technical": "Technical problems", "no_match": "Neither action"}
  },
  "state": "I have a question about an invoice."
}
```

`expertBean`, `operation`, and `state` are required. Omitted `parameters` defaults to `{}`. State accepts text, objects, or arrays according to expert capabilities. Parameters contain literal JSON, including nested values; preview does not expand placeholders. The selected expert defines the parameters and their defaults.

Each request creates a native `SemanticEvaluation` in an isolated Camel context. Its state selector is `${body}`. The context has no action routes and evaluates the native expression directly. Preview accepts no executable YAML and never dispatches actions. It exposes neither expert discovery nor batch evaluation.

A choice success returns:

```json
{"resultType":"choice","value":"billing","diagnostics":{}}
```

`resultType` comes from the expert contract. Values retain their native types:

| Result type | JSON value |
| --- | --- |
| `boolean` | Boolean |
| `choice` | String |
| `score` | Number |
| `classification` | Array of label strings, including an empty array |

Diagnostics include only expert-supplied `probability`, `probabilities`, and `confidence`. WSR does not invent diagnostics.

The old `input`/`instructions`/`criteria`/`message` request and `label` response contract no longer apply. Update Barn clients alongside deployment.

| Failure | HTTP status / error |
| --- | --- |
| Invalid request, disabled expert, unsupported operation, invalid parameters or input | 400 / `invalid_request`, before inference |
| Capacity exhaustion | 429 |
| Provider failure or malformed evaluation result | 502 / `evaluation_failed` |
| Evaluation timeout | 504 / `evaluation_timeout` |

Preview retains Bearer authentication, a 64 KiB request limit, timeout cancellation, and bounded concurrency. A cancelled evaluation retains its capacity slot until its worker exits.
