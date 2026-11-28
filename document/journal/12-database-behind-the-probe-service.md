# 12: A database behind the probe service

*22 to 28 November 2026, milestone 9*

## Summary

My professor asked for a database, and on 22 November he confirmed by email
what he means: the service under test accesses the database while it processes
requests. It is not a configuration source. He did not say which database or
whether the service reads or writes, so I chose. probe-service 0.3.0 now writes
one row to PostgreSQL for every `GET /probe`.

| | Result |
|---|---|
| Database | PostgreSQL 17.2, one replica in the namespace `data`, on the control-plane node |
| Table | `probe_request` with `id`, `received_at`, `pod`, `config_version`, created by Flyway |
| What a request does | Inserts one row, with `statement_timeout` set to the effective `timeoutMs` |
| Database down or too slow | `/probe` answers 503 with `{"error":"database"}` |
| Framework | Spring Boot 4.2.0 and Spring Cloud 2026.0.0 in probe-service and config-server |
| Images | `probe-service:0.3.0`, `config-server:0.2.0` |
| Ways | All four Deployments get `DB_URL`, `DB_USER` and `DB_PASSWORD` |
| New tests | 11 in three classes, against a PostgreSQL container |
| Table between runs | Emptied by the runner before every run, since 28 November |
| Runs on the cluster | S1 smoke campaign on 28 November, results below |

The code is in three commits of 23 November: `0f924ff` (database),
`5e203ee` (Spring Boot 4) and `d41a11d` (database settings in the Ways). The
reasons are the three entries of 2026-11-23 in
[docs/DECISIONS.md](../../docs/DECISIONS.md#2026-11-23-probe-service-writes-one-row-to-postgresql-per-request).
[Previous entry](11-scenarios-from-incident-reports.md).

## What was built

| Part | What | Files |
|---|---|---|
| Database | Namespace `data`, Secret `postgres-credentials`, Service `postgres`, StatefulSet with a 1 Gi volume | `deploy/data/00-namespace.yaml`, `deploy/data/postgres.yaml` |
| Schema | One Flyway migration | `services/probe-service/src/main/resources/db/migration/V1__create_probe_request.sql` |
| Write path | `ProbeRequestRepository.save` with `JdbcClient`, called by `ProbeController` before it answers | `ProbeRequest.java`, `ProbeRequestRepository.java`, `ProbeController.java` |
| Timeout | `ConfigRules.effectiveTimeoutMs()` | `ConfigRules.java` |
| Connection | `spring.datasource` from `DB_URL`, `DB_USER`, `DB_PASSWORD`, pool wait 1000 ms | `application.yml` |
| Tests | `ConfigRulesTest`, `ProbeRequestRepositoryTest`, `ProbeStoresRequestTest`, helper `TestDatabase` | `services/probe-service/src/test/` |
| Ways | Secret `probe-db` in `thesis` and three variables per Deployment | `deploy/probe-db-secret.yaml`, `deploy/way-*/` |

## What one request does now

1. `GET /probe` reaches `ProbeController`.
2. A transaction starts. It sets the PostgreSQL `statement_timeout` for this
   transaction to the effective `timeoutMs`: the configured value when it lies in
   [100, 30000], and 1000 when it is out of range or not a number.
3. It inserts one row with the pod name and the configuration version that the
   answer carries. The database sets `received_at`.
4. The controller answers as before, with the same five fields.

If the pool gets no connection within 1000 ms, or the insert runs into the
statement timeout, the controller answers 503 with `{"error":"database"}`.
The test with a locked table shows this: with `timeoutMs` 200 the answer is 503
after 200 to 1500 ms, and 200 again once the lock is gone.

This gives `timeoutMs` a real effect. Before, it was only checked by the
readiness gate. An invalid value such as `abc` makes the pod unready when the
gate is on, but the request itself still writes its row with 1000 ms.

## What changed per Way

Every Way gets the database settings in the same way, independent of how it
delivers the configuration under test: `DB_URL` as a plain value, `DB_USER` and
`DB_PASSWORD` from the Secret `probe-db`.

| Way | File | Change |
|---|---|---|
| A | `deploy/way-a-envvar/deployment.yaml` | Image 0.3.0, three variables |
| B | `deploy/way-b-volume/deployment.yaml` | Image 0.3.0, three variables next to `SPRING_CONFIG_IMPORT` |
| C | `deploy/way-c-configserver/deployment.yaml` | Image 0.3.0, three variables. The Config Server moves to `config-server:0.2.0` on Spring Boot 4 |
| D | `deploy/way-d-gitops/chart/` | Chart 0.2.0 with `appVersion` 0.3.0, image 0.3.0 in `values.yaml`, three variables in the template |

The Way D chart is also in the configuration repository. On the branch `way-d`
the commit "probe-service 0.3.0 with database" of 23 November has the same
template, values and `Chart.yaml` as this repository. The Secret `probe-db` is
not in the chart, and the AppProject would not let Argo CD create a Secret.

The approach descriptors in `approaches/` did not change. None of them lists
the `postgres` StatefulSet as infrastructure.

## Spring Boot 4

The database commit pinned Testcontainers and Flyway on Spring Boot 3.3.4.
Seven minutes later both services moved to Spring Boot 4.2.0, and the pins went
away. The changes in the code are new names and packages: the starters
`webmvc`, `flyway` and `jdbc-test`, the health classes, Jackson 3 in the tests
(`asText()` became `asString()`) and Testcontainers 2. Why the upgrade came then
is not recorded.

## What was not checked

- The unit tests of probe-service, until 28 November. Then `mvn -o test` ran
  29 tests with no failure, including the four against a PostgreSQL 17.2
  container. config-server has no tests and only compiles.
- What a pod does when the database is not there at start. Flyway runs at
  start, so I expect the pod not to start; not tried.
- Whether the extra insert changes the window or the failed-request numbers.
  All earlier smoke results are from 0.2.0 on Spring Boot 3.3.4.
- Way C on Spring Boot 4: the Config Server and the bus over RabbitMQ.
- Load on the control-plane node with PostgreSQL and Loki on it together.
- How large the table gets in a full campaign. Since 28 November the runner
  empties it before every run, so it holds one run at most.

## Emptying the table before every run

On 28 November I added one core step to the runner. Before, every run added
about 12,000 rows and nothing removed them, so a full campaign would have ended
with about 5.6 million rows on the 1 GiB volume, and the last run would have
written into a much bigger table than the first. The reason and the rejected
options are in
[docs/DECISIONS.md](../../docs/DECISIONS.md#2026-11-28-the-runner-empties-the-probe_request-table-before-every-run).

| Part | What | Files |
|---|---|---|
| Protocol | New object `database` with namespace `data`, pod `postgres-0`, container `postgres`, user `thesis`, database `probe`, table `probe_request`; `protocolVersion` stays 1 | `protocol/protocol-v1.json`, `Protocol.java` |
| Command | `ClusterControl.emptyTable` runs `psql` through `kubectl exec` with a limit of 120 s; the SQL truncates `probe_request` if it exists | `ClusterControl.java` |
| Order | After the old pods are gone, before the reset of the approach | `Campaign.java` |
| Tests | The command and its timeout, the protocol fields, a failure that stops the campaign before the reset, the new step in the run order | `ClusterControlTest`, `ProtocolTest`, `CampaignTest`, `RunProcedureTest` |

The step runs while no probe-service pod exists, so nothing writes to the table
at that moment. It skips a table that is not there yet: on a new cluster Flyway
creates it only at the first start of probe-service, which comes after the step.

I checked it in a real campaign the same afternoon: one run per Way with S1
(`20261128-160724_verify-table-reset`, 4 of 4 valid). A loop counted the rows
every 20 seconds. The count rose to about 11,500 to 12,200 in each run and fell
to 0 at the start of the next one; it never added up across runs. A failure stops the campaign like every other core step. The
same day the [experiment runbook](../runbook-experiment-1.md) got a check that
applies `deploy/data/` and `deploy/probe-db-secret.yaml`, and its image tag is
now 0.3.0.

## Smoke campaign with the database

The first attempt on 2026-11-28 stopped before its first run. `InvariantCheck`
found image 0.3.0 in the Deployment and 0.2.0 in the protocol file. I changed the
protocol file, see the
[decision entry](../../docs/DECISIONS.md#2026-11-28-the-protocol-file-names-image-030-and-keeps-protocol-version-1-for-the-smoke-runs).

The second attempt ran from 14:48 to 15:47 with all four Ways: 8 runs in 2
blocks, all VALID, exit code 0. One run of Way A has the flag `LOW_RESOLUTION`.
The folder is `results/raw/20261128-144821_s1-smoke`. It was the first S1 run of
Way D.

Medians of 2 runs per Way:

| Way | P1, all replicas on v2 | P2, first replica on v2 | C1, inconsistency window | A1, failed requests |
|---|---|---|---|---|
| A | 46.9 s | 15.7 s | 31.2 s | 0 |
| B | 90.9 s | 90.8 s | 0.1 s | 0 |
| C | 6.1 s | 5.1 s | 1.0 s | 0 |
| D | 55.6 s | 20.2 s | 35.4 s | 0 |

Compared with the smoke campaign of 2026-11-15 without the database, Way A went
from 30.1 s to 46.9 s for P1 and from 20.9 s to 31.2 s for C1. Way C went from
4.3 s to 6.1 s. Way B did not change. Ways A and D replace pods, and a pod of
image 0.3.0 also has to connect to the database and run Flyway before it is
ready. That would explain why the two restart Ways got slower. I have not
measured the start time of a pod, so this is my explanation and not a result.
The two campaigns ran with different images and 2 runs each, so the difference
is a hint only.

The table `probe_request` had 33 rows before the campaign and 97 720 after it.
Argo CD synced four times, all inside the two Way D runs. No sync fell into a
run of Way A, B or C.

## Open points

1. Nothing applies `deploy/data/` or `deploy/probe-db-secret.yaml` by itself.
   Since 28 November the [experiment runbook](../runbook-experiment-1.md), the
   [Way A runbook](../runbook-way-a.md#2-build-and-deploy), the
   [Way D runbook](../runbook-way-d.md#prerequisites) and the quick start in
   [document/README.md](../README.md) say to apply them by hand. The same day the
   documents moved to `probe-service:0.3.0`, and the
   [probe API page](../probe-api.md#what-a-request-writes) got the database step,
   the 503 answer and the role of `timeoutMs`.
2. The runner does not check the database before a run. In S3 it is not an
   infrastructure component of any Way, and if it is down every Way fails in the
   same way.
3. The database is not part of the readiness group. A pod without a database
   stays ready and answers 503. Whether that is right for the experiment is
   open.
4. The database user and password are in Git in two Secrets as plain text.
5. My professor has not seen how I read his email: one write per request,
   PostgreSQL, settings outside the delivery path.
6. The full campaigns come after everything else. Their estimate of about 31
   hours was made with 0.2.0 and may change.
