---
title: Deploy on Kubernetes and OpenShift
description: Run the Wanaku Semantic Router runtime on Kubernetes or OpenShift with the example Deployment, the health probes, and safe rolling updates.
---

# Deploy on Kubernetes and OpenShift

This page describes how to run the WSR runtime on Kubernetes or OpenShift, with the example Deployment and the health probes.

Use [the deployment example](https://github.com/wanaku-ai/wanaku-semantic-router/blob/main/config/kubernetes.yaml.example) to supply the route name and service addresses through environment settings. The container needs only the `runtime` command. Provider credentials use Secret references. The example needs no properties file or ConfigMap. Replace its image and route name. Use an image that your cluster can pull. Create the referenced `wsr-provider` Secret with the key `api-key`. Its `model` key is optional. Apply the Deployment and Service in the namespace that contains the referenced Barn and Wanaku services.

The Service address `http://wsr:8090/mcp` must be reachable from Wanaku. The example sets `WSR_STATUS_BIND=0.0.0.0` because the kubelet sends probes to the pod IP. Do not publish the status port through a Service. Use separate Deployments for runtime and preview. Add the preview token to its Secret and configure Barn with the matching reference.

The example keeps catalog storage in the container's writable temporary directory. A restarted process downloads and verifies its pinned publication again. The image supports the UID assigned by OpenShift. The example does not set a fixed UID or group. If you add a volume, configure its permissions for the UID and groups assigned by your cluster.

## Health probes

Kubernetes, OpenShift, and the Wanaku Barn operator read runtime health from the status listener. The listener uses `wsr.status.bind` (`WSR_STATUS_BIND`, default `127.0.0.1`) and `wsr.status.port` (`WSR_STATUS_PORT`, default `8091`). Set `WSR_STATUS_BIND=0.0.0.0` in a cluster.

Each probe endpoint accepts `GET` only. Other methods get `405`. Paths must match exactly. Other paths get `404`. Each response has the same JSON body as `/api/v1/status`. `/api/v1/status` always returns `200`.

| Endpoint | `200` when | `503` when |
| --- | --- | --- |
| `GET /api/v1/health/started` | `state` is `ready` | `starting`, `failed`, `stopping`, or `stopped` |
| `GET /api/v1/health/ready` | `state` is `ready` and the Camel context is started | Any other condition |
| `GET /api/v1/health/live` | `state` is `starting`, or `state` is `ready` and the Camel context is started | `failed`, `stopping`, `stopped`, or `ready` with a stopped Camel context |

WSR sets `state` to `ready` only after these steps succeed: catalog load, dependency resolution, Camel start, the MCP self-check, and forward registration. Registration must report an available forward with one discovered tool. WSR registers with `POST /api/v1/forwards?replace=true`, so a restarted runtime replaces a forward that a killed process did not remove. A failed step sets `state` to `failed`. The process then exits with status `1`. The status body contains the failure type in `failure`.

SIGTERM makes all three probes return `503` before WSR removes its forward. Kubernetes then removes the pod from Service endpoints while deregistration runs. WSR checks the `wsr.instance` label before it removes the forward. It does not remove a forward that a replacement process registered.

On a startup failure, WSR writes the status JSON to `/dev/termination-log`, truncated to 4096 bytes. The operator reads the failure from `lastState.terminated.message`. Set `terminationMessagePolicy: FallbackToLogsOnError` for images where that path is not writable.

The recommended container settings are:

```yaml
terminationGracePeriodSeconds: 60  # pod spec: at least 2 x wsr.http.timeout-ms plus the Camel stop time
containers:
  - name: wsr
    env:
      - { name: WSR_BIND, value: "0.0.0.0" }
      - { name: WSR_STATUS_BIND, value: "0.0.0.0" }
      - { name: WSR_MCP_ADDRESS, value: "http://<service>.<namespace>.svc:8090/mcp" }
    ports:
      - { name: mcp, containerPort: 8090 }
      - { name: status, containerPort: 8091 }
    startupProbe:  # Maven dependency resolution can be slow
      httpGet: { path: /api/v1/health/started, port: status }
      periodSeconds: 5
      failureThreshold: 120
    readinessProbe:
      httpGet: { path: /api/v1/health/ready, port: status }
      periodSeconds: 5
    livenessProbe:
      httpGet: { path: /api/v1/health/live, port: status }
      periodSeconds: 20
      failureThreshold: 3
    terminationMessagePolicy: FallbackToLogsOnError
```

Run one replica for each forward name. A `RollingUpdate` strategy with `maxSurge: 1` and `maxUnavailable: 0` is safe. The new pod registers the same forward name with a new `wsr.instance` label. The old pod sees the different label at shutdown and leaves the forward registered.

WSR does not detect a forward that is deleted from Wanaku after `ready`. The probes stay at `200` in that case. Wanaku's reconnect behavior handles forwards that become unavailable.

## Next steps

- [Run WSR in a container](container.md): build the image and check it.
- [Runtime configuration](../reference/configuration.md): the environment settings of the runtime.
