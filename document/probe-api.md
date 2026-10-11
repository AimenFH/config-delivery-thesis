# Probe service API

The probe service has no business logic. It exists to report its own configuration
state, so that every request is also a measurement.

## Base URL

| Where | URL |
|---|---|
| Local (`mvn spring-boot:run` or `docker run -p 8080:8080`) | `http://localhost:8080` |
| kind cluster, Way A | `http://localhost:30080` |

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

The invalid-configuration scenario uses this endpoint to measure how long a replica
takes to notice that it holds a broken configuration.

## Actuator endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/readiness` | Readiness, used by the Kubernetes readiness probe (available when running in Kubernetes) |
| `POST /actuator/refresh` | Reload configuration (activation trigger for Way B) |
| `GET /actuator/prometheus` | Metrics in Prometheus format |

## Configuration properties

| Property | Default | Env var (Way A) |
|---|---|---|
| `demo.value` | `UNSET` | `DEMO_VALUE` |
| `demo.configVersion` | `v0` | `DEMO_CONFIGVERSION` |
| `demo.timeoutMs` | `1000` | `DEMO_TIMEOUTMS` |

Defaults live in `services/probe-service/src/main/resources/application.yml`.
`v0` and `UNSET` mean that no external configuration reached the replica.
