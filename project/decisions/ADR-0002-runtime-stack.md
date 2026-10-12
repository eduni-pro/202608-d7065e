# ADR-0002 — Runtime and language: Java 25 with MicroProfile / Quarkus

- **Status:** Accepted
- **Date:** 2026-08-31 (renumbered from 0001 when the decision logs were merged, ADR-0007)
- **Decision owner:** Wassim Akachi
- **Scope:** All project-authored containers (physics-simulator, co2-sensor, damper-actuator, dcv-controller, fault-monitor, supervisor, dashboard)

> **Amended 2026-10-10** — the decision stands. This record chose "MicroProfile APIs on the Quarkus
> runtime" without pinning a version, and `project/pom.xml` pinned 3.39.5. The pin moves to
> **3.40.1**, the platform whose BOM manages the MicroProfile 7.0 component versions, so that
> MicroProfile Telemetry 2.0 is available. See ADR-0011; the "MicroProfile Config, Health and
> Reactive Messaging are standard APIs" consequence below is unchanged, and the Reactive Messaging
> half of it was already corrected by the 2026-10-05 amendment.

## Context

The project builds a demand-controlled ventilation system over six rooms of an office floor
that detects, diagnoses and degrades gracefully around faults in its own equipment (ADR-0001),
on top of **BuildSim**, a single Go binary that holds building state in memory and exposes it
over REST plus a WebSocket for the 3D viewer (`tutorials/buildsim.md`). BuildSim supplies
rooms and equipment but **no physics** — the simulator, the sensing, the decision logic, the
data pipeline and the history are all processes this project writes.

Two course requirements constrain the runtime choice before anything else does:

1. **Per-component OS-process isolation.** Every sensor, gateway and actuator must run as its
   own containerized process, independently start/stop/restartable, such that a fault in one
   does not take down the others
   (`lab-assignment/final_report_guide/report_guide.tex:384`).
2. **The C4 Container diagram must map 1:1 to `docker-compose.yml`**
   (`lab-assignment/final_report_guide/report_guide.tex:259`, `:496` — and the guide
   requires the same 1:1 mapping onto the **repository folders**), with every arrow labelled with its protocol.

Scale is small and fixed by the domain: one office floor, on the order of 10–60 zones sampled
at roughly 1 Hz. The course's own worked example reaches its breaking point at **~55 zones,
bottlenecked on the database**, not on the runtime
(`lab-assignment/final_report_example/final_report_example.tex:900`).

Assessment adds a third constraint: the 30-minute oral is individual, with no slides, no notes
and no AI. Any technology in the design is a technology that must be defended unaided at a
whiteboard, and the course states plainly that sophistication earns no marks.

## Decision

**Use Java 25 with MicroProfile APIs on the Quarkus runtime, one container per component,
built and run under Docker Compose.**

The framework is applied where it earns its keep, not uniformly:

| Component | Stack | Rationale |
|---|---|---|
| `co2-sensor`, `damper-actuator` | Plain Java `main` + MQTT client | ~100-line processes; CDI and a framework would be pure overhead |
| `physics-simulator` | Plain Java `main` + its own tick loop | Nothing it does is a framework concern: it owns a loop and a seeded random source, and the evaluation harness has to drive the same tick method directly with a simulated clock, which a managed scheduler would obstruct |
| `dcv-controller`, `fault-monitor`, `supervisor`, `dashboard` | Quarkus + MicroProfile Scheduler, Config, Health; MQTT through Eclipse Paho in `shared` | Scheduling, configuration and liveness/readiness are standard-API concerns |

**Amended 2026-10-05 to match what was built.** Two corrections, both recorded rather than
quietly applied:

- The simulator was listed as Quarkus with Scheduler and is plain Java. The reason is the one
  above: the tick has to be callable directly against a `SimulatedClock`, because that is what
  makes R-3's seven-day replay and R-1's twenty injection runs measurable at all.
- Pub/sub was listed as MicroProfile Reactive Messaging via `quarkus-messaging-mqtt`, and is
  Eclipse Paho behind a port in `shared`. MicroProfile has no MQTT API — the connector is
  SmallRye's, not a specification — so the standard-API argument does not actually apply to
  this transport. Paho then serves the plain-Java processes and the Quarkus services through
  one code path, where the extension would have served only half of them and left the devices
  needing a second client. The dependency is justified under the hierarchy because steps one
  to three offer nothing for MQTT.

**Build mode:** JVM mode during development, **native image only for the final footprint
measurement**. Native builds cost minutes each and must not become the inner development loop.

**Concurrency inside a container:** virtual threads (Java 21+). Concurrency is a within-container
concern; isolation is a between-container concern, and the two are deliberately not conflated.

## Related decisions

### MQTT (Mosquitto) as the device→service transport

**MQTT over a Mosquitto broker was chosen because the device processes (`co2-sensor`,
`damper-actuator`) must be independently start/stop/restartable OS processes whose failure does
not take down the others (`lab-assignment/final_report_guide/report_guide.tex:384`), and because
R-4 — readings stale beyond three intervals put the monitor in `BLIND` with no diagnosis — needs
a transport in which a dead publisher simply goes silent instead of blocking a caller; the
alternatives were direct REST between processes and Kafka; the trade-off accepted is no
persistence or replay in the broker, and ordering guaranteed per topic only.**

Direct REST makes every consumer's outage the publisher's problem and turns silence into a
timeout the caller must handle; Kafka supplies replay and partitioning that six rooms at a few
tens of messages per second never need, at the price of the heaviest process in the deployment.

*Defence in one sentence:* a sensor that dies must go quiet, not hang its consumers, and
pub/sub is the transport in which silence is the failure mode; the broker costs one more process
and gives no replay.

### TimescaleDB as the hot store

**TimescaleDB was chosen because R-5 — every diagnosis reconstructable from inputs, command,
residual series, classification and timestamp for any `caseId` — and R-6 — the zone count at
which the monitor misses its 60 s interval, where the course's own worked example broke on the
per-zone database query (`lab-assignment/final_report_example/final_report_example.tex:900`) —
both need time-window SQL over an append-only series (plan §2, constraint 2: TimescaleDB hot,
Parquet cold); the alternatives were plain PostgreSQL and InfluxDB; the trade-off accepted is
one more image to run, and SQL window functions over hypertables instead of a purpose-built
time-series database.**

Plain PostgreSQL answers the same queries without chunked time partitioning, so the window scan
grows with history rather than with the window; InfluxDB is built for the series but joins the
four streams a reconstruction needs poorly and has no JDBC path.

**Amended 2026-10-12 — the decision stands, and the R-6 half of the justification above does
not.** This record assumed the monitor would read a per-zone window out of the store on its
cycle, which is what made R-6 a database argument. It never did, and the built code says so:
`FaultMonitor` and `ZoneMonitor` call `ResidualJournalPort.append` and nothing else
(`fault-monitor/.../control/FaultMonitor.java:82,87,95,114`, `ZoneMonitor.java:724,734`), the
detector's residual window is held in memory by `ZoneObserver`, and the only runtime caller of
`replayCase`/`replayZone` is the dashboard serving R-5
(`dashboard/.../control/CaseReplay.java:44`). The monitor's relation to the journal is
**append-only**. T-28 then measured the cycle saturating on `AhuFlowModel.believedFlowTo`, an
in-memory Θ(n²) sum, with reading ingestion at 0.89 ms p95 at 25 600 zones — three orders of
magnitude under the cycle — so R-6's ceiling is not in the store at all. **R-5 is what carries
this choice on its own**: a reconstruction is time-bounded ordered scans over `readings`,
`commands` and `residuals` across one case's window, joined to the `decisions` and `strategies`
rows that carry its `case_id` (`TimescaleJournalAdapter.replayCase`). That is still SQL over
time-partitioned data and still the thing plain PostgreSQL would answer more slowly as history
grows, so the alternatives stand; only the requirement they were weighed against changes.

*Defence in one sentence:* reconstructing a diagnosis is a time-window join across four series,
which is SQL over time-partitioned data; the price is one more image and a hypertable instead of
a purpose-built store.

## Alternatives considered

### Erlang / OTP — rejected

Genuinely stronger than Java at supervised lightweight processes, hot code upgrade and
let-it-crash recovery. It was rejected because those strengths do not map onto this
assessment:

- **The isolation layer is at the wrong level.** OTP supervision operates *inside* the BEAM.
  Using it as intended — many sensor processes supervised in one node — puts N sensors in one
  Unix process and **violates requirement 1**. Satisfying requirement 1 instead means one BEAM
  node per sensor container, which is the heaviest possible host for a single actor and leaves
  a supervision tree supervising one child. Either way the supervision layer duplicates
  isolation that Docker restart policies already provide, and earns no marks for it.
- **The scale argument does not apply.** Millions of processes answers a problem this domain
  does not have (10–60 zones at 1 Hz), and BuildSim — one in-memory Go server behind REST — is
  the ceiling regardless of what the client runtime can hold.
- **Mnesia is a poor fit for the history.** Grades 4 and 5 require quantitative evaluation over
  stored history. Mnesia has no time-series compression and no SQL for those queries, a 2 GB
  `dets` ceiling on `disc_only_copies`, and manual network-partition recovery. It also couples
  storage to the BEAM cluster — the exact coupling containerization exists to break.
- **Erlang distribution is not demonstrable.** `disterl` is a full-mesh, shared-cookie,
  all-or-nothing-trust fabric. Replacing MQTT with it turns a live, inspectable protocol
  boundary (`mosquitto_sub` during the demo) into an opaque one, and leaves the Container
  diagram's protocol labels unhelpful.
- **It raises the oral surface area.** Choosing OTP obliges a defence of restart strategies,
  links vs monitors and max-restart-intensity — questions the examiner is entitled to ask
  precisely because the choice was made.

### Go, or Go + Python (the course example's stack) — not chosen

Viable and proven against this exact assignment. Rejected on personal-fluency grounds: the
oral caps the grade at what can be defended unaided, and Java is the stack this author can
defend to depth. Fluency is a legitimate architectural input when the assessment is individual
and oral.

## Consequences

**Positive**

- Requirement 1 is satisfied structurally, by the container boundary, with no second isolation
  mechanism to explain or justify.
- Native images at roughly 30–50 MB RSS make the container count pushable far enough to
  actually locate the system's breaking point — a grade-4/5 measurable obtained as a
  by-product: *RSS per container × N containers → laptop saturation*.
- Every protocol boundary stays inspectable and demonstrable from the command line.
- MicroProfile Config, Health and Reactive Messaging are standard APIs, so the justification is
  "standard API" rather than "framework feature".

**Negative / accepted risks**

- GraalVM native builds are slow and memory-hungry; mitigated by keeping them out of the
  development loop and off the critical path until the evaluation phase.
- Java has a larger baseline container footprint than Go for the trivial sensor processes. This
  is accepted and will be *measured* rather than argued, since footprint per container feeds
  the breaking-point experiment directly.
- Two build modes (JVM and native) must both be kept working in CI.

## Follow-up

- The breaking-point experiment must confirm that the bottleneck is the datastore or the
  broker, **not** the runtime. That measurement is what retires the "why not Erlang?" question
  at the oral; without it, this ADR is an assertion rather than a finding.
- The transport and storage decisions are recorded above; the control-approach decision is
  recorded separately.
