# Probe service API

The probe service has no business logic. It exists to report its own configuration
state, so that every request is also a measurement.

This page describes version 0.2.0 (`probe-service:0.2.0`). The same image runs in
every approach. Compared with 0.1.0 it has one addition, the
[readiness gate](#the-readiness-gate), which is off unless `CONFIG_GATE_ENABLED`
is `true`.

## Base URL

| Where | URL |
|---|---|
| Local (`mvn spring-boot:run` or `docker run -p 8080:8080`) | `http://localhost:8080` |
| kind cluster, every approach | `http://localhost:30080` |

The API has no authentication and no rate limit.

## `GET /probe`

Returns the configuration this replica is using right now.

```bash
curl -s localhost:30080/probe
```

Response `200 OK`:

```json
{
  "value": "hello",
  "configVersion": "v1",
  "pod": "probe-service-6c79fdddb-g6jlg",
  "startedAt": 1791726032129,
  "servedAt": 1791726285312
}
```

| Field | Type | Meaning |
|---|---|---|
| `value` | string | The configured value (`demo.value`) |
| `configVersion` | string | The configured version (`demo.configVersion`). The window is detected by comparing this field across responses. |
| `pod` | string | Pod name (from `HOSTNAME`), or `local` outside Kubernetes. The middle part of the name identifies the Deployment revision. |
| `startedAt` | number | Epoch milliseconds when the process started. Changes on restart, stays the same on refresh. |
| `servedAt` | number | Epoch milliseconds on the pod's clock when it answered. Used only as a secondary signal. |

In Ways B and C the controller is rebuilt lazily. After a refresh, the new values
appear on the first request that reaches the replica, not at refresh time.

## `GET /config-health`

Validates the configuration this replica is using right now.

```bash
curl -s -w "  HTTP %{http_code}\n" localhost:30080/config-health
```

Response `200 OK` when the configuration is valid:

```json
{"status": "VALID", "configVersion": "v1"}
```

Response `503 Service Unavailable` when the configuration is invalid:

```json
{
  "status": "INVALID",
  "violations": [
    "demo.timeoutMs='abc' is not an integer",
    "demo.configVersion='1.0' does not match v<number>"
  ]
}
```

### Validation rules

| Property | Rule |
|---|---|
| `demo.timeoutMs` | Integer between 100 and 30000 |
| `demo.configVersion` | Matches `v<number>`, for example `v1`, `v42` |

`/config-health` answers the same way with the gate on and off. The experiment
runner does not call it. Scenario S2 sees an invalid configuration through the
version in the `/probe` answer and through the readiness gate below.

## The readiness gate

The gate makes a replica report "not ready" while it holds an invalid
configuration. Kubernetes then takes the pod out of the Service, so the invalid
configuration has an effect that can be measured from outside. Without the gate an
invalid `timeoutMs` changes nothing: `/probe` answers 200 and the pod stays ready.

| | |
|---|---|
| Switch | Environment variable `CONFIG_GATE_ENABLED` |
| On | The value is exactly `true` |
| Off | The variable is unset or has any other value, for example `false` |
| Rules | The [validation rules](#validation-rules) of `/config-health` |
| Class | `ConfigGateHealthIndicator`, health contributor `configGate` |

The switch is read when the process starts. The experiment runner sets the
variable on the Deployment after every deploy, to the value of the scenario file
(`true` in S2, `false` in S1 and S3), and waits for the rollout. Nobody sets it in
the manifests.

### What `/actuator/health/readiness` answers

This is the path of the Kubernetes readiness probe, which runs every 2 s. The
command below is for a local process. In the cluster, port 30080 only reaches pods
that are ready.

```bash
curl -s -w "  HTTP %{http_code}\n" localhost:8080/actuator/health/readiness
```

| Gate | Configuration | Status | Body |
|---|---|---|---|
| off | valid | `200` | `{"status":"UP"}` |
| off | invalid, for example `timeoutMs` `abc` | `200` | `{"status":"UP"}` |
| on | valid | `200` | `{"status":"UP"}` |
| on | invalid | `503` | `{"status":"DOWN"}` |

With the gate off the answer is the same as in 0.1.0, and `configGate` does not
appear in `/actuator/health` at all. With the gate on, the readiness body still
shows only the status. The violations are listed under `components.configGate` in
`/actuator/health`:

```json
{"status": "DOWN", "details": {"violations": ["demo.timeoutMs='abc' is not an integer"]}}
```

Liveness (`/actuator/health/liveness`) does not include the gate and stays `200`,
so Kubernetes does not restart a pod because of an invalid configuration.

### The gate after a refresh

The rules are checked by `ConfigRules`, which is refresh-scoped. After
`POST /actuator/refresh` the gate judges the new values. A pod that was ready
becomes not ready when a refresh brings an invalid value, and ready again when a
later refresh brings a valid one. `GateRefreshTest` covers this for
`/actuator/refresh`. There is no unit test for a Bus refresh. In the S2 smoke run
the three Way C pods stopped receiving requests after the Bus refresh brought
`timeoutMs` `abc` and answered again after the fix, see
[journal entry 06](journal/06-experiment-protocol.md).

The later refresh has to reach the pod. In Way B it does not: the Configuration
Watcher sends its refresh only to ready pods, so a pod that the gate has made not
ready stays on the invalid values until someone calls `/actuator/refresh` on it
directly. See [journal entry 07](journal/07-way-b-gate-deadlock.md).

`/probe` is not affected by the gate. A pod that is not ready still answers
`/probe` with 200 when it is asked directly. It only stops receiving requests
through the Service.

## Actuator endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/readiness` | Readiness, used by the Kubernetes readiness probe. Includes the [readiness gate](#the-readiness-gate) when it is on |
| `GET /actuator/health/liveness` | Liveness. Never includes the gate |
| `POST /actuator/refresh` | Reload configuration (activation trigger for Way B) |
| `POST /actuator/busrefresh` | Not on probe-service. It is called on the Config Server, which sends the refresh to every replica over RabbitMQ (Way C) |
| `GET /actuator/prometheus` | Metrics in Prometheus format |

## Configuration properties

| Property | Default | Env var (Way A) |
|---|---|---|
| `demo.value` | `UNSET` | `DEMO_VALUE` |
| `demo.configVersion` | `v0` | `DEMO_CONFIGVERSION` |
| `demo.timeoutMs` | `1000` | `DEMO_TIMEOUTMS` |

Defaults live in `services/probe-service/src/main/resources/application.yml`.
`v0` and `UNSET` mean that no external configuration reached the replica.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `CONFIG_GATE_ENABLED` | unset (gate off) | `true` adds the readiness gate to the readiness group |
| `CONFIG_CLIENT_ENABLED` | `false` | Turns the Config Server client on (Way C) |
| `BUS_ENABLED` | `false` | Turns Spring Cloud Bus and the RabbitMQ health check on (Way C) |
| `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `rabbitmq.platform.svc`, `thesis`, `thesis` | RabbitMQ connection for the Bus |
| `HOSTNAME` | set by Kubernetes | Becomes the `pod` field, or `local` when it is missing |

## Tests of the gate

| Test class | Covers |
|---|---|
| `GateOffReadinessTest` | Variable unset or `false`: readiness is 200 with the 0.1.0 body, also with `timeoutMs` `abc` |
| `GateOnReadinessTest` | Gate on: 200 for valid values and the limits 100 and 30000, 503 for `abc`, 99, 30001 and versions that do not match `v<number>` |
| `GateRefreshTest` | Gate on: readiness follows the values after `/actuator/refresh`. Gate off: readiness stays 200 after a refresh to an invalid value |

Run them with `mvn test` in `services/probe-service/`.
