# ADR-0011 — Operational telemetry is OpenTelemetry metrics pushed to Prometheus, published from `boundary/` only

- **Status:** Accepted · 2026-10-10
- **Decision owner:** Wassim Akachi
- **Scope:** The four Quarkus services (`dcv-controller`, `fault-monitor`, `supervisor`, `dashboard`), the platform version in `project/pom.xml`, and `project/docker-compose.yml`
- **Implemented by:** issue #90

> **Recorded after submission.** The report went to v1.0 on 2026-10-07 and the deadline passed on
> 2026-10-08. This record therefore changes nothing the report claims; it is a decision about the
> running system, taken afterwards, and the decision log exists to show the path the design took
> (`README.md`). Any figure it produces belongs in a later revision, not in v1.0.

## Context

The system's interesting behaviour is a control loop that is currently only legible after the
fact. A reader who wants to know what the detector did reads
`project/evaluation/results/T-23-demo-run-261416e.txt` — a captured transcript of fifteen journal
exports. Watching the loop *while it runs* means tailing `mosquitto_sub` and querying TimescaleDB
by hand. The CUSUM statistic that crosses `h=150`, the case state walking
`WARMUP → HEALTHY → SUSPECT → CONFIRMED → COMPENSATING → SERVICED`, and the `BLIND` state that
R-4 defines are all computed in process and then discarded; only their consequences reach a topic
or a table.

Three facts about the deployment shape the choice before anything else does.

**The modules are not a uniform runtime.** ADR-0002 deliberately applies the framework only where
it earns its keep, and the reactor splits accordingly:

| Module | Runtime |
|---|---|
| `dcv-controller`, `fault-monitor`, `supervisor`, `dashboard` | Quarkus + `quarkus-arc`, `quarkus-scheduler`, `quarkus-smallrye-health` |
| `co2-sensor`, `damper-actuator`, `physics-simulator` | plain `java -jar` main, no CDI container |
| `evaluation` | plain main, batch harness |
| `shared` | library |

**The evaluation harness drives the control layer directly.** Counting its own imports,
`evaluation` reaches into `diagnosis.control` 14 times, `supervision.control` 9, `physics.control`
6 and `ventilation.control` 4, against a single import from `diagnosis.boundary`. Every published
requirement figure — R-1's detection delays, R-3's twenty fault-free weeks, R-6's zone ceiling of
58 — is produced on that path.

**MicroProfile moved.** In MicroProfile 7.0, MicroProfile Telemetry 2.0 *replaced* MicroProfile
Metrics as the umbrella's metrics specification; MP Metrics 5.1 left the umbrella and continues as
a standalone spec. Telemetry 2.0 defines no API artifact of its own — it adopts the OpenTelemetry
API. Reading the Quarkus 3.40.1 platform BOM confirms exactly that: it manages
`microprofile-config-api` 3.1.1, `microprofile-health-api` 4.0.1,
`microprofile-fault-tolerance-api` 4.1.2, `microprofile-openapi-api` 4.1.1 and
`microprofile-rest-client-api` 4.0 — the MicroProfile 7.0 component versions — together with
`opentelemetry-api` and `opentelemetry-sdk` 1.62.0, and **zero** artifacts matching
`microprofile-metrics` or `microprofile-telemetry`.

## Decision

**OpenTelemetry metrics, written against the OpenTelemetry API on the Quarkus 3.40.1 platform,
pushed over OTLP/http to Prometheus's native receiver and read in Grafana, published from
`boundary/` classes only, from the four Quarkus services only, was chosen because the detection
loop must be legible while it runs rather than only in a captured transcript; the alternatives
were MicroProfile Metrics, a Micrometer registry scraped at `/q/metrics`, a separate MQTT-to-Prometheus
bridge service, and an OpenTelemetry Collector in the path; the trade-off accepted is that metrics
become a push rather than a scrape, so Prometheus's `up` signal is lost and liveness stays the job
of the existing `quarkus-smallrye-health` endpoints.**

Four parts of that are separate decisions and are recorded separately.

### 1. The platform moves to Quarkus 3.40.1, and with it to MicroProfile 7.0

ADR-0002 chose "Java 25 with MicroProfile APIs on the Quarkus runtime" without pinning a version;
`project/pom.xml` pinned 3.39.5. The pin moves to **3.40.1**, which is the version whose BOM
manages the MicroProfile 7.0 component versions quoted above. Taking MicroProfile Telemetry
without taking MicroProfile 7.0 is not a coherent position — Telemetry 2.0 is what makes a release
MicroProfile 7, since it is the specification that displaced MP Metrics there.

`quarkus-arc`, `quarkus-smallrye-health`, `quarkus-rest`, `quarkus-opentelemetry` and
`quarkus-micrometer-opentelemetry` are already resolved locally at 3.40.1; `quarkus-scheduler`,
`quarkus-junit5` and `quarkus-maven-plugin` are not, so the bump needs network and is a commit of
its own with the full suite as its gate.

### 2. The OpenTelemetry API, not Micrometer

Metrics are written against `io.opentelemetry.api.metrics.Meter` through the single
`quarkus-opentelemetry` extension. `quarkus-micrometer-opentelemetry` exists and would bridge the
Micrometer API onto the same OTLP exporter, and it is not used: it means writing against a second,
non-MicroProfile API to reach the same wire format. One API, and it is the standard one.

**Dependency justification**, under the hierarchy in `CLAUDE.md`. Java SE offers no metrics wire
format — JMX has neither OTLP nor Prometheus encoding, and no dimensional labels, so a Grafana
panel per zone is not expressible. Jakarta EE has no metrics specification at all. MicroProfile
Telemetry 2.0 is the answer at step 3, which is where this resolves; no external-library exception
is needed. Prometheus and Grafana are container images and change the Maven graph not at all.

### 3. Instrumentation lives in `boundary/`, as observable gauges

Each service gets one `*Metrics` class in its `boundary/` package, registering **observable
(callback) gauges** that read control-layer state at collection time. `control/` gains no OTel
import.

This follows from the context rather than from taste. The evaluation harness drives `control/`
directly — 33 control-layer imports against one boundary import — so instrumentation confined to
`boundary/` cannot touch the path that produced R-1, R-3 and R-6 **by construction**, and the
published figures need no re-measurement to stay honest. It also preserves the port seams recorded
in `project/diagrams/seams.md`: the control layer stays unit-testable with no OTel SDK on the
classpath, which is the framework-independence principle ADR-0005 already applies at every
boundary crossing. Callback gauges are the instrument that makes this possible — the boundary pulls
from control when the exporter asks, instead of control pushing into a framework object it would
then have to know about.

The cost is a concurrency obligation: those callbacks run on the OTel collection thread, so any
state they read must be safely published rather than merely visible on the loop's own thread.

### 4. Prometheus receives OTLP directly; no collector

Services push OTLP/http; Prometheus accepts it on `/api/v1/otlp/v1/metrics` with
`--web.enable-otlp-receiver`. Two new compose services rather than three, both bound to
`127.0.0.1` like every other port in `docker-compose.yml`.

### What each service publishes

The rule is that a number is published by the component that computes it:

| Service | Signals |
|---|---|
| `fault-monitor` | CUSUM statistic and residual per zone; case state over the seven-state walk plus `BLIND`; confirmations by fault type; detection delay; probe requests; blind zones; cycle duration against `CYCLE_SECONDS=60` |
| `dcv-controller` | commanded damper position; control cycle duration against `control.seconds=1`; journal write latency and failures; MQTT publish failures |
| `supervisor` | strategy changes by mode; trimmed position; outer-loop duration; BuildSim call latency and failures; service reports |
| `dashboard` | zone CO₂; fan power; fan speed command; MQTT messages received; projection age |

The plant view sits in `dashboard` because `DashboardRuntime` already subscribes to six of the
eight filters in `Topics` and `LiveProjection` already holds the state. A ninth container
subscribing to the same topics would be a second MQTT consumer for data already in process.

### What is deliberately not instrumented

`co2-sensor`, `damper-actuator` and `physics-simulator` have no CDI container, so MicroProfile
Telemetry is not available to them without hand-wiring the OTel SDK. The stronger reason is that
they sit on the far side of a **contract seam** (`project/diagrams/seams.md`): they stand in for
hardware, and a real CO₂ sensor on a wall does not export OTLP. Instrumenting them would measure
the simulator rather than the system, and everything they produce is observable from the bus
anyway. `evaluation` is a batch harness whose output is result files; metrics describe a process
that keeps running, and it ends.

## Alternatives rejected

Unlike ADR-0009 and ADR-0010, these are rejected on reasoning and on the repository as it stands,
not on experiment. Nothing here has been measured yet, and the record says so rather than
implying otherwise.

- **MicroProfile Metrics 5.1.** The specification this project would have reached for a year ago,
  and it is no longer in the umbrella — MicroProfile 7.0 replaced it with Telemetry 2.0. Choosing
  it now means choosing the displaced spec and explaining at a whiteboard why the standard answer
  was passed over. The Quarkus 3.40.1 BOM manages no `microprofile-metrics` artifact at all.
- **`quarkus-micrometer-registry-prometheus`, scraped at `/q/metrics`.** Operationally the
  simplest option: a scrape endpoint per service, no push, and Prometheus's `up` metric for free.
  Rejected because it is not OpenTelemetry and not MicroProfile — it is a Quarkus extension
  wrapping a third-party registry, which puts the dependency justification at step 4 of the
  hierarchy when step 3 answers the question. Worth re-opening only if the push model proves
  awkward in practice; the loss of `up` is noted as a cost below.
- **A separate MQTT-to-Prometheus bridge service.** Architecturally attractive: it observes the
  published contract in `Topics` rather than any implementation, needs no change to the eight
  existing modules, and would be the purest expression of the contract seam. Rejected because it
  can only see what is published, and the signals worth watching are the ones that are not — the
  CUSUM statistic, the state transitions, cycle overrun, journal latency. It would also be a
  second MQTT consumer for data `dashboard` already holds.
- **An OpenTelemetry Collector between the services and Prometheus.** The conventional topology,
  and it buys fan-out, batching and processor pipelines. Rejected as a third container earning
  nothing here: there is one backend, one protocol and six zones.
- **Instrumenting `control/` directly, which is where the numbers are.** The shortest path to a
  gauge and the one that would put OTel imports on the exact code the evaluation harness drives.
  Rejected to keep the published requirement figures untouched by construction, and to keep the
  control layer testable without an OTel SDK.

## Trade-off accepted

**Push, not scrape, so `up` is gone.** Prometheus cannot tell a service that is healthy and quiet
from one that has died, because nothing is scraped. Liveness remains the job of the existing
`quarkus-smallrye-health` endpoints, which `docker-compose.yml` already health-checks against, so
this costs no new mechanism — but it does mean a Grafana dashboard alone is not a liveness view,
and should not be presented as one.

**A build-time, tech-preview switch.** `quarkus.otel.metrics.enabled` is build-time, so the
decision to export is baked into the image rather than configured at run time, and it is still
marked tech preview on this Quarkus line.

**A second naming convention to keep straight.** OpenTelemetry instrument names use dots and
arrive in Prometheus as underscores with unit suffixes appended, so
`ventilation.detection.delay` is queried as `ventilation_detection_delay_seconds`. Harmless once
known, confusing while looking for a panel that appears empty.

**Cardinality is now a standing risk.** Six zones and a seven-state enum are trivial. A label
carrying `case_id`, a timestamp or anything per-event is unbounded and would make the time series
database the first thing to fall over — in a project whose own breaking-point result (R-6, 58
zones) is a database bottleneck.

**One open question partially answered.** `README.md`'s "Still to decide" asks whether the
dashboard should be "Grafana over the store vs a Quarkus application vs BuildSim's own viewer
alone". This record does not settle it and does not re-open it: the Quarkus `dashboard` writing
BuildSim's room layers remains the occupant-facing view, and Grafana enters beside it as the
*operator's* view of the control loop's internals. Two views with different audiences, not a
replacement.

## Consequences

**Positive.** The detection loop becomes watchable live, which is the one thing the captured
transcript cannot be. The signals that justify the design — a CUSUM crossing its limit, a case
state advancing, a cycle fitting inside its budget — become visible rather than argued. Standard
APIs throughout, so the justification at the oral is "the MicroProfile specification for this"
rather than "a framework feature".

**Negative / accepted.** Two more containers in a compose file whose C4 Container diagram must map
to it 1:1 (`report_guide.tex:259`), so the diagram and the runbook both acquire work that the
report at v1.0 does not show. Three Quarkus modules gain a dependency and a `boundary/` class
each. The platform bump touches all nine modules at once and its only gate is the test suite.

## Defence in one sentence

The system's interesting state — the CUSUM statistic, the case state, the cycle margin — is
computed in process and thrown away, so it chose MicroProfile Telemetry 2.0's OpenTelemetry API on
the Quarkus 3.40.1 platform, pushed straight to Prometheus's own OTLP receiver with no collector,
and confined the instrumentation to `boundary/` observable gauges because the evaluation harness
drives `control/` directly and every published requirement figure comes off that path — which
means the four Quarkus services publish and the three device and plant processes do not, since
they stand in for hardware that would never export OTLP in the first place.

## Follow-up

This record is a decision, not a finding. What has to be measured before it can claim one:

- **R-6's zone ceiling, re-checked.** The published 58-zone figure is a database bottleneck
  measured on an uninstrumented monitor. The `boundary/` confinement argues it cannot have moved;
  arguing is not measuring, and the re-check is cheap.
- **The cycle budgets under export.** `dcv-controller` runs at `control.seconds=1` and
  `fault-monitor` at `CYCLE_SECONDS=60`. Collection must not eat into either.
- **A `FAULT_SPEC` run read off Grafana**, with the CUSUM crossing `h=150` and the state reaching
  `CONFIRMED` visible on the panel, is what turns this record's Measured section from absent into
  real.
